package net.chetch.xmpp;

import net.chetch.messaging.Message;

import org.jivesoftware.smack.chat2.Chat;
import org.jivesoftware.smack.chat2.IncomingChatMessageListener;
import org.jxmpp.jid.EntityBareJid;

public interface IChetchOutgoingMessageListener {

    public void onOutgoingMessage(EntityBareJid from, org.jivesoftware.smack.packet.MessageBuilder builder, Chat chat);
}
