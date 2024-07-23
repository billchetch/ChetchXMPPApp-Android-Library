package net.chetch.xmpp.models;

import android.content.Context;

import androidx.lifecycle.MutableLiveData;

import net.chetch.messaging.Message;
import net.chetch.messaging.MessageFilter;
import net.chetch.messaging.filters.CommandResponseFilter;
import net.chetch.xmpp.ChetchXMPPViewModel;

public class GPSViewModel extends ChetchXMPPViewModel {
    public static final String SERVICE_NAME = "GPS XMPP Service";

    public class Position{

    }


    public void init(Context context, String username, String password){
        super.init(context, username, password, SERVICE_NAME);

        //addMessageFilter(statusResponse);
    }
}
