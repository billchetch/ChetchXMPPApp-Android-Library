package net.chetch.xmpp.exceptions;

import net.chetch.messaging.Message;

public class ChetchXMPPViewModelException extends ChetchXMPPException{
    Message sourceMessage;

    public ChetchXMPPViewModelException(String message){
        super(message);
    }

    public ChetchXMPPViewModelException(Message errorMessage)
    {
        super(errorMessage != null && errorMessage.hasValue("Message") ? errorMessage.getString("Message") : "No source message supplied!");
        sourceMessage = errorMessage;
    }

}
