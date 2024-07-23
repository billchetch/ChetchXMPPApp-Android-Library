package net.chetch.xmpp.models;

import android.content.Context;

import net.chetch.xmpp.ChetchXMPPViewModel;

public class ADMViewModel extends ChetchXMPPViewModel {
    public static final String TEST_SERVICE_NAME = "ADM Test XMPP Service";

    protected String serviceName = TEST_SERVICE_NAME;

    public void init(Context context, String username, String password){
        super.init(context, username, password, serviceName);

        //addMessageFilter(statusResponse);
    }
}
