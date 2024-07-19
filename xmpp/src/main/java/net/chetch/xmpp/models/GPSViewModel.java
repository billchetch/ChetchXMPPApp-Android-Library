package net.chetch.xmpp.models;

import android.content.Context;

import androidx.lifecycle.MutableLiveData;

import net.chetch.xmpp.ChetchXMPPViewModel;

public class GPSViewModel extends ChetchXMPPViewModel {
    public static final String SERVICE_NAME = "GPS XMPP Service";
    public static final String COMMAND_LATEST_POSITION = "";

    MutableLiveData<Object> latestPosition;

    public void init(Context context, String username, String password){
        super.init(context, username, password, SERVICE_NAME);
    }

    MutableLiveData<Object> getLatestOsition(){
        //sendCommand("");
        return latestPosition;
    }
}
