Name:       {{aurora.org}}.{{aurora.app}}
Summary:    {{aurora.launcher_name}}
Release:    {{aurora.release}}
Version:    {{aurora.version}}
Group:      Amusements/Games
License:    Proprietary

BuildRequires: patchelf

{{#aurora.requires_exclude}}{{{aurora.requires_exclude}}}
{{/aurora.requires_exclude}}%define __provides_exclude_from ^%{_datadir}/%{name}/lib/.*\.so.*$

%description
{{aurora.launcher_name}} for Aurora OS (Defold engine bundle).

%build

%install
install -m 0755 -D dmengine %{buildroot}%{_bindir}/%{name}
patchelf --force-rpath --set-rpath %{_datadir}/%{name}/lib %{buildroot}%{_bindir}/%{name}

# Game content (game.projectc, archives, bundle resources)
install -d %{buildroot}%{_datadir}/%{name}
cp -r content/. %{buildroot}%{_datadir}/%{name}/
# cp preserves host permissions; rpm-validator rejects group/world-writable files
chmod -R u=rwX,go=rX %{buildroot}%{_datadir}/%{name}

# Bundled shared libraries (picked up via the rpath set above)
{{{aurora.lib_installs}}}

# Icons (all four sizes are required by rpm-validator)
install -m 644 -D icons/86.png  %{buildroot}%{_datadir}/icons/hicolor/86x86/apps/%{name}.png
install -m 644 -D icons/108.png %{buildroot}%{_datadir}/icons/hicolor/108x108/apps/%{name}.png
install -m 644 -D icons/128.png %{buildroot}%{_datadir}/icons/hicolor/128x128/apps/%{name}.png
install -m 644 -D icons/172.png %{buildroot}%{_datadir}/icons/hicolor/172x172/apps/%{name}.png

install -m 644 -D {{aurora.org}}.{{aurora.app}}.desktop %{buildroot}%{_datadir}/applications/%{name}.desktop

%files
%defattr(-,root,root,-)
%attr(755,root,root) %{_bindir}/%{name}
%{_datadir}/applications/%{name}.desktop
%{_datadir}/%{name}
%{_datadir}/icons/hicolor/86x86/apps/%{name}.png
%{_datadir}/icons/hicolor/108x108/apps/%{name}.png
%{_datadir}/icons/hicolor/128x128/apps/%{name}.png
%{_datadir}/icons/hicolor/172x172/apps/%{name}.png
