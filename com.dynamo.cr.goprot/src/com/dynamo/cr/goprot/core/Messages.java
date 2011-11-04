package com.dynamo.cr.goprot.core;

import org.eclipse.osgi.util.NLS;

public class Messages extends NLS {
    private static final String BUNDLE_NAME = "com.dynamo.cr.goprot.core.messages"; //$NON-NLS-1$

    public static String NodeModel_ResourceValidator_tileSet_NOT_FOUND;
    public static String NodeModel_ResourceValidator_tileSet_NOT_SPECIFIED;

    static {
        // initialize resource bundle
        NLS.initializeMessages(BUNDLE_NAME, Messages.class);
    }

    private Messages() {
    }
}
