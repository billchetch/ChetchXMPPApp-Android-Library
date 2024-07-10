package net.chetch.xmpp;

import org.jivesoftware.smack.ConnectionListener;

public interface IChetchConnectionListener extends ConnectionListener {
    public void connectFailed(Exception e);
    public void authenticationFailed(Exception e);
}
