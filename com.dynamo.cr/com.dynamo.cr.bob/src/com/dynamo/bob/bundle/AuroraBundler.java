// Copyright 2020-2026 The Defold Foundation
// Copyright 2014-2020 King
// Copyright 2009-2014 Ragnar Svensson, Christian Murray
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
//
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

package com.dynamo.bob.bundle;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;

import com.dynamo.bob.Bob;
import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.Platform;
import com.dynamo.bob.Project;
import com.dynamo.bob.fs.IResource;
import com.dynamo.bob.logging.Logger;
import com.dynamo.bob.pipeline.ExtenderUtil;
import com.dynamo.bob.util.BobProjectProperties;
import com.dynamo.bob.util.Exec;

/**
 * Aurora OS bundler: assembles an RPM build tree next to the bundle output,
 * renders the .spec/.desktop templates from builtins and drives sfdk
 * (build-init/prepare/build, rpmsign-external, rpm-validator).
 *
 * Application settings come from the [aurora] section of game.project
 * (org/app/version/release/permissions/orientation, icons, template overrides).
 * Machine-local settings (sdk path, target, signing credentials) are CLI
 * options only: --aurora-sdk, --aurora-target, --aurora-cert, --aurora-key,
 * --aurora-key-pass. The signing key passphrase is passed to rpmsign-external
 * via the KEY_PASSPHRASE environment variable and is never put on a command
 * line or into build artifacts.
 */
@BundlerParams(platforms = {"arm64-aurora"})
public class AuroraBundler implements IBundler {

    private static final Logger logger = Logger.getLogger(AuroraBundler.class.getName());

    private static final String[] ICON_SIZES = {"86", "108", "128", "172"};

    // Godot port precedent: AuroraOS-<version>(-base|-MB2)?-<arch>
    private static final Pattern TARGET_NAME = Pattern.compile(".*AuroraOS-([0-9.]+)(-base|-MB2)?-(armv7hl|x86_64|aarch64).*");

    // We ship only arm64-aurora (see @BundlerParams); the policy is "oldest compatible"
    private static final int[] MIN_TARGET_VERSION = {5, 1, 0, 0};

    @Override
    public IResource getManifestResource(Project project, Platform platform) throws IOException {
        return null;
    }

    @Override
    public String getMainManifestName(Platform platform) {
        return null;
    }

    @Override
    public String getMainManifestTargetPath(Platform platform) {
        return null;
    }

    @Override
    public void updateManifestProperties(Project project, Platform platform,
                                BobProjectProperties projectProperties,
                                Map<String, Map<String, Object>> propertiesMap,
                                Map<String, Object> properties) throws IOException {
    }

    private static int[] parseVersion(String s) {
        String[] parts = s.split("\\.");
        int[] v = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                v[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                v[i] = 0;
            }
        }
        return v;
    }

    private static int compareVersions(int[] a, int[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static Exec.Result runTool(Map<String, String> env, File workDir, String... args) throws IOException, CompileExceptionError {
        Exec.Result result = Exec.execResultWithEnvironmentWorkDir(env, workDir, args);
        String output = new String(result.stdOutErr, StandardCharsets.UTF_8);
        if (result.ret != 0) {
            // Note: never pass secrets via args; the signing passphrase travels in env only
            throw new CompileExceptionError(String.format("Command failed with exit code %d: %s\n%s", result.ret, String.join(" ", args), output));
        }
        logger.info("%s", output);
        return result;
    }

    /**
     * Pick the minimal target with version >= MIN_TARGET_VERSION for the platform
     * architecture from `sfdk engine exec sb2-config -l` (snapshot entries with
     * the .default suffix are skipped).
     */
    private static String autodetectTarget(String sdk, Platform platform) throws IOException, CompileExceptionError {
        String arch;
        if (platform == Platform.Arm64Aurora) {
            arch = "aarch64";
        } else {
            throw new CompileExceptionError("Unsupported Aurora platform: " + platform.getPair());
        }
        Exec.Result result = Exec.execResultWithEnvironment(new HashMap<String, String>(), sdk, "engine", "exec", "sb2-config", "-l");
        if (result.ret != 0) {
            throw new CompileExceptionError(String.format("Failed to list Aurora SDK targets (exit code %d): %s", result.ret, new String(result.stdOutErr, StandardCharsets.UTF_8)));
        }
        String output = new String(result.stdOutErr, StandardCharsets.UTF_8);
        String best = null;
        int[] bestVersion = null;
        for (String line : output.split("\\r?\\n")) {
            String name = line.trim();
            if (name.endsWith(".default")) {
                continue;
            }
            Matcher m = TARGET_NAME.matcher(name);
            if (!m.matches() || !m.group(3).equals(arch)) {
                continue;
            }
            int[] version = parseVersion(m.group(1));
            if (compareVersions(version, MIN_TARGET_VERSION) < 0) {
                continue;
            }
            if (bestVersion == null || compareVersions(version, bestVersion) < 0) {
                best = name;
                bestVersion = version;
            }
        }
        if (best == null) {
            throw new CompileExceptionError(String.format("No Aurora SDK target >= 5.1.0.0 for %s found; install one or pass --aurora-target", arch));
        }
        logger.info("Auto-selected Aurora SDK target: %s", best);
        return best;
    }

    /**
     * Copy bundled shared libraries into <rpm build dir>/lib and return their file names.
     * Default source is /libexec/<platform>/lib/ inside bob.jar; --aurora-bundle-libs
     * adds (or overrides by file name) libraries from the host file system.
     */
    private static List<String> collectBundledLibs(Project project, Platform platform, File libDir) throws IOException, CompileExceptionError {
        List<String> names = new ArrayList<>();
        String prefix = "libexec/" + platform.getPair() + "/lib";
        URL libRoot = Bob.class.getResource("/" + prefix);
        if (libRoot != null && libRoot.getProtocol().equals("jar")) {
            JarURLConnection jarConnection = (JarURLConnection) libRoot.openConnection();
            jarConnection.setUseCaches(false);
            try (JarFile jarFile = jarConnection.getJarFile()) {
                List<String> entries = new ArrayList<>();
                for (JarEntry entry : jarFile.stream().toList()) {
                    String name = entry.getName();
                    if (!entry.isDirectory() && name.startsWith(prefix + "/") && name.indexOf('/', prefix.length() + 1) < 0) {
                        entries.add(name);
                    }
                }
                for (String entry : entries) {
                    String fileName = entry.substring(prefix.length() + 1);
                    URL resourceUrl = Bob.class.getResource("/" + entry);
                    if (resourceUrl != null) {
                        libDir.mkdirs();
                        Bob.atomicCopy(resourceUrl, new File(libDir, fileName), false);
                        names.add(fileName);
                    }
                }
            }
        } else if (libRoot != null && "file".equals(libRoot.getProtocol())) {
            // Development mode: classes are not packed into a jar
            File[] files = new File(libRoot.getPath()).listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        libDir.mkdirs();
                        FileUtils.copyFile(file, new File(libDir, file.getName()));
                        names.add(file.getName());
                    }
                }
            }
        }

        String userLibs = project.option("aurora-bundle-libs", "");
        for (String path : userLibs.split(",")) {
            path = path.trim();
            if (path.isEmpty()) {
                continue;
            }
            File file = new File(path);
            if (!file.isFile()) {
                throw new CompileExceptionError("Option --aurora-bundle-libs: file not found: " + path);
            }
            libDir.mkdirs();
            FileUtils.copyFile(file, new File(libDir, file.getName()));
            if (!names.contains(file.getName())) {
                names.add(file.getName());
            }
        }
        return names;
    }

    // libopenal.so.1 -> libopenal (the part before the first ".so")
    private static String libBaseName(String fileName) {
        int i = fileName.indexOf(".so");
        return i > 0 ? fileName.substring(0, i) : fileName;
    }

    private void copyIcons(Project project, File iconsDir) throws IOException, CompileExceptionError {
        iconsDir.mkdirs();
        for (String size : ICON_SIZES) {
            File dst = new File(iconsDir, size + ".png");
            String cliPath = project.option("aurora-icon-" + size, null);
            if (cliPath != null) {
                File src = new File(cliPath);
                if (!src.isFile()) {
                    throw new CompileExceptionError(String.format("Option --aurora-icon-%s: file not found: %s", size, cliPath));
                }
                FileUtils.copyFile(src, dst);
            } else {
                IResource iconResource = project.getResource("aurora", "app_icon_" + size + "x" + size);
                FileUtils.writeByteArrayToFile(dst, iconResource.getContent());
            }
        }
    }

    @Override
    public void bundleApplication(Project project, Platform platform, File bundleDir, ICanceled canceled)
            throws IOException, CompileExceptionError {

        BobProjectProperties projectProperties = project.getProjectProperties();

        String org = projectProperties.getStringValue("aurora", "org", "").trim();
        String app = projectProperties.getStringValue("aurora", "app", "").trim();
        if (org.isEmpty() || app.isEmpty()) {
            throw new CompileExceptionError("Aurora OS bundle requires 'org' and 'app' in the [aurora] section of game.project (package name will be <org>.<app>)");
        }
        if (!org.matches("[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)*") || !app.matches("[a-zA-Z0-9_-]+")) {
            throw new CompileExceptionError(String.format("Invalid Aurora package name '%s.%s': only letters, digits, '_' and '-' are allowed, dots only inside org", org, app));
        }
        String packageName = org + "." + app;

        String title = projectProperties.getStringValue("project", "title", "Unnamed");
        String version = projectProperties.getStringValue("aurora", "version", "1.0.0").trim();
        String release = projectProperties.getStringValue("aurora", "release", "1").trim();
        String permissions = projectProperties.getStringValue("aurora", "permissions", "").trim();
        if (permissions.isEmpty()) {
            logger.warning("Aurora: 'permissions' in the [aurora] section of game.project is empty; the application will run without system permissions");
        }
        String orientation = projectProperties.getStringValue("aurora", "orientation", "").trim();
        if (orientation.isEmpty()) {
            int width = projectProperties.getIntValue("display", "width", 960);
            int height = projectProperties.getIntValue("display", "height", 540);
            orientation = width > height ? "LandscapeInverted;Landscape" : "Portrait";
        }

        String sdk = project.option("aurora-sdk", "sfdk");
        String cert = project.option("aurora-cert", null);
        String key = project.option("aurora-key", null);
        if (cert == null || key == null) {
            throw new CompileExceptionError("Aurora OS bundle requires --aurora-cert and --aurora-key (RPM signing is mandatory)");
        }
        if (!new File(cert).isFile()) {
            throw new CompileExceptionError("Option --aurora-cert: file not found: " + cert);
        }
        if (!new File(key).isFile()) {
            throw new CompileExceptionError("Option --aurora-key: file not found: " + key);
        }
        String target = project.option("aurora-target", null);
        if (target == null || target.trim().isEmpty()) {
            target = autodetectTarget(sdk, platform);
        }
        // build wants the plain target name, signing/validation work on the snapshot
        target = target.replaceAll("\\.default$", "");

        BundleHelper.throwIfCanceled(canceled);

        // The RPM build tree must live under the host home directory: the build
        // engine sees host paths as-is (shared home)
        File rpmDir = new File(bundleDir, packageName);
        String home = System.getProperty("user.home");
        if (home != null && !rpmDir.getCanonicalPath().startsWith(new File(home).getCanonicalPath() + File.separator)) {
            logger.warning("Aurora: bundle output '%s' is outside of the home directory; sfdk requires the build tree under the shared home", rpmDir.getAbsolutePath());
        }
        FileUtils.deleteDirectory(rpmDir);
        rpmDir.mkdirs();

        // Engine binary
        final String variant = project.option("variant", Bob.VARIANT_RELEASE);
        List<File> bundleExes = ExtenderUtil.getNativeExtensionEngineBinaries(project, platform);
        if (bundleExes == null) {
            bundleExes = Bob.getDefaultDmengineFiles(platform, variant);
        } else if (variant.equals(Bob.VARIANT_DEBUG)) {
            File debugEngine = new File(bundleExes.get(0).getParent(), "dmengine_unstripped");
            bundleExes.set(0, debugEngine);
        }
        if (bundleExes.size() != 1) {
            throw new IOException("Invalid number of binaries for Aurora OS when bundling: " + bundleExes.size());
        }
        FileUtils.copyFile(bundleExes.get(0), new File(rpmDir, "dmengine"));

        BundleHelper.throwIfCanceled(canceled);

        // Game content
        File contentDir = new File(rpmDir, "content");
        contentDir.mkdirs();
        File buildDir = new File(project.getRootDirectory(), project.getBuildDirectory());
        if (BundleHelper.isArchiveIncluded(project)) {
            for (String name : BundleHelper.getArchiveFilenames(buildDir)) {
                FileUtils.copyFile(new File(buildDir, name), new File(contentDir, name));
            }
        }
        final List<Platform> architectures = Platform.getArchitecturesFromString(project.option("architectures", ""), platform);
        Map<String, IResource> bundleResources = ExtenderUtil.collectBundleResources(project, architectures);
        ExtenderUtil.writeResourcesToDirectory(bundleResources, contentDir);

        BundleHelper.throwIfCanceled(canceled);

        // Bundled shared libraries
        List<String> bundledLibs = collectBundledLibs(project, platform, new File(rpmDir, "lib"));

        // Icons
        copyIcons(project, new File(rpmDir, "icons"));

        // Render templates
        Map<String, Map<String, Object>> propertiesMap = projectProperties.createTypedMap(new BobProjectProperties.PropertyType[]{BobProjectProperties.PropertyType.BOOL});
        Map<String, Object> aurora = propertiesMap.get("aurora");
        if (aurora == null) {
            aurora = new HashMap<>();
            propertiesMap.put("aurora", aurora);
        }
        aurora.put("org", org);
        aurora.put("app", app);
        aurora.put("version", version);
        aurora.put("release", release);
        aurora.put("permissions", permissions);
        aurora.put("orientation", orientation);
        aurora.put("launcher_name", title);
        if (!bundledLibs.isEmpty()) {
            StringBuilder excludes = new StringBuilder("%define __requires_exclude ");
            StringBuilder installs = new StringBuilder();
            for (int i = 0; i < bundledLibs.size(); i++) {
                String lib = bundledLibs.get(i);
                if (i > 0) {
                    excludes.append("|");
                }
                excludes.append("^").append(libBaseName(lib).replace(".", "\\.")).append(".*\\.so.*$");
                installs.append(String.format("install -m 644 -D lib/%s %%{buildroot}%%{_datadir}/%%{name}/lib/%s%n", lib, lib));
            }
            aurora.put("requires_exclude", excludes.toString());
            aurora.put("lib_installs", installs.toString());
        } else {
            aurora.put("requires_exclude", "");
            aurora.put("lib_installs", "");
        }

        IResource specResource = project.getResource("aurora", "spec");
        File specDir = new File(rpmDir, "rpm");
        specDir.mkdirs();
        File specFile = new File(specDir, packageName + ".spec");
        FileUtils.write(specFile, BundleHelper.formatResource(propertiesMap, new HashMap<>(), specResource.getContent(), specResource.getPath()), StandardCharsets.UTF_8);

        IResource desktopResource = project.getResource("aurora", "desktop");
        File desktopFile = new File(rpmDir, packageName + ".desktop");
        FileUtils.write(desktopFile, BundleHelper.formatResource(propertiesMap, new HashMap<>(), desktopResource.getContent(), desktopResource.getPath()), StandardCharsets.UTF_8);

        BundleHelper.throwIfCanceled(canceled);

        // Build the RPM (build-init once per build tree, marked by the .sfdk directory)
        Map<String, String> env = new HashMap<>();
        if (!new File(rpmDir, ".sfdk").exists()) {
            runTool(env, rpmDir, sdk, "-c", "target=" + target, "build-init");
        }
        runTool(env, rpmDir, sdk, "-c", "target=" + target, "prepare");
        runTool(env, rpmDir, sdk, "-c", "target=" + target, "build");

        File rpmsDir = new File(rpmDir, "RPMS");
        File[] rpms = rpmsDir.listFiles((d, name) -> name.startsWith(packageName + "-") && name.endsWith(".rpm"));
        if (rpms == null || rpms.length != 1) {
            throw new CompileExceptionError("Expected exactly one built RPM in " + rpmsDir.getAbsolutePath());
        }
        File rpm = rpms[0];
        String rpmRelPath = "RPMS/" + rpm.getName();

        // Sign; the key passphrase (if any) is passed via the environment only
        Map<String, String> signEnv = new HashMap<>();
        String keyPass = project.option("aurora-key-pass", null);
        if (keyPass != null) {
            signEnv.put("KEY_PASSPHRASE", keyPass);
        }
        runTool(signEnv, rpmDir, sdk, "engine", "exec", "sb2", "-t", target + ".default", "rpmsign-external", "sign", "-c", cert, "-k", key, rpmRelPath);

        // Validate
        runTool(env, rpmDir, sdk, "engine", "exec", "sb2", "-t", target + ".default", "rpm-validator", "-p", "regular", rpmRelPath);

        FileUtils.copyFile(rpm, new File(bundleDir, rpm.getName()));
        logger.info("Aurora OS bundle created: %s", new File(bundleDir, rpm.getName()).getAbsolutePath());

        BundleHelper.moveBundleIfNeed(project, bundleDir);
    }
}
