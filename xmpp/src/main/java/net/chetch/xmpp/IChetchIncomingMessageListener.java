package net.chetch.xmpp;

import net.chetch.messaging.Message;

import org.jivesoftware.smack.chat2.IncomingChatMessageListener;
import org.jxmpp.jid.EntityBareJid;
import org.jivesoftware.smack.chat2.Chat;

public interface IChetchIncomingMessageListener{
    public void onIncomingMessage(EntityBareJid from, Message message,  org.jivesoftware.smack.packet.Message originalMessage, Chat chat);
}
