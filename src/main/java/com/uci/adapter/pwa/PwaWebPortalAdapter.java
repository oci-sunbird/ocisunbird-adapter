package com.uci.adapter.pwa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.uci.adapter.provider.factory.AbstractProvider;
import com.uci.adapter.provider.factory.IProvider;
import com.uci.adapter.pwa.web.outbound.PwaWebResponse;
import com.uci.adapter.pwa.web.inbound.PwaWebMessage;
import com.uci.adapter.pwa.web.outbound.OutboundMessage;
import com.uci.adapter.pwa.web.outbound.PwaMessage;
import com.uci.adapter.pwa.web.outbound.PwaWebResponse;
import com.uci.adapter.utils.PropertiesCache;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import messagerosa.core.model.*;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Mono;

import javax.xml.bind.JAXBException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Function;

@Slf4j
@Getter
@Setter
@Builder
public class PwaWebPortalAdapter extends AbstractProvider implements IProvider {

    @Autowired
    @Qualifier("rest")
    private RestTemplate restTemplate;
    
    private String assesOneLevelUpChar;
    private String assesGoToStartChar;
    

    @Override
    public Mono<XMessage> convertMessageToXMsg(Object message) throws JAXBException, JsonProcessingException {
        PwaWebMessage webMessage = (PwaWebMessage) message;
        SenderReceiverInfo from = SenderReceiverInfo.builder().deviceType(DeviceType.PHONE_PWA).build();
        SenderReceiverInfo to = SenderReceiverInfo.builder().userID("admin").build();
        XMessage.MessageState messageState = XMessage.MessageState.REPLIED;
        MessageId messageIdentifier = MessageId.builder().build();

        XMessagePayload xmsgPayload = XMessagePayload.builder().build();
        log.info("XMessage Payload getting created >>>");
        xmsgPayload.setText(webMessage.getText());
        XMessage.MessageType messageType= XMessage.MessageType.TEXT;
        //Todo: How to get Button choices from normal text
        from.setUserID(webMessage.getFrom());
        
        /* To use later in outbound reply message's message id & to */
        messageIdentifier.setChannelMessageId(webMessage.getMessageId());
        messageIdentifier.setReplyId(webMessage.getFrom());
        
        XMessage x = XMessage.builder()
                .to(to)
                .from(from)
                .channelURI("web")
                .providerURI("pwa")
                .messageState(messageState)
                .messageId(messageIdentifier)
                .messageType(messageType)
                .timestamp(Timestamp.valueOf(LocalDateTime.now()).getTime())
                .payload(xmsgPayload).build();
        log.info("Current message :: " +  x.toString());
        return Mono.just(x);
    }

    @Override
    public Mono<XMessage> processOutBoundMessageF(XMessage xMsg) throws Exception {
        log.info("Sending message to transport socket :: " + xMsg.toXML());
        OutboundMessage outboundMessage = getOutboundMessage(xMsg);
        log.info("Sending final xmessage to transport socket :: " + xMsg.toXML());
        String url = System.getenv("PWA_TRANSPORT_SOCKET_BASE_URL")+"/botMsg/adapterOutbound";
        return PwaWebService.getInstance().
                sendOutboundMessage(url, outboundMessage)
                .map(new Function<PwaWebResponse, XMessage>() {
            @Override
            public XMessage apply(PwaWebResponse pwaWebResponse) {
                if(pwaWebResponse != null){
                    xMsg.setMessageId(MessageId.builder().channelMessageId(pwaWebResponse.getId()).build());
                    xMsg.setMessageState(XMessage.MessageState.SENT);
                }
                return xMsg;
            }
        });
    }


    @Override
    public void processOutBoundMessage(XMessage nextMsg) throws Exception {
        log.info("next question to user is {}", nextMsg.toXML());
        callOutBoundAPI(nextMsg);
    }

    public XMessage callOutBoundAPI(XMessage xMsg) throws Exception{
        OutboundMessage outboundMessage = getOutboundMessage(xMsg);
        String url = System.getenv("PWA_TRANSPORT_SOCKET_BASE_URL")+"/adapterOutbound";
        PwaWebService webService = new PwaWebService();
        PwaWebResponse response = webService.sendText(url, outboundMessage);
        if(null != response){
            xMsg.setMessageId(MessageId.builder().channelMessageId(response.getId()).build());
        }
        xMsg.setMessageState(XMessage.MessageState.SENT);
        return xMsg;
    }

    private OutboundMessage getOutboundMessage(XMessage xMsg) throws JAXBException {
        PwaMessage pwaMessage = PwaMessage.builder()
        									.title(getTextMessage(xMsg))
        									.choices(this.getButtonChoices(xMsg))
        									.build();
        return OutboundMessage.builder()
        		.message(pwaMessage)
				.to(xMsg.getMessageId().getReplyId())
				.messageId(xMsg.getMessageId().getChannelMessageId())
				.build();
    }
    
    /**
     * Get Simplified Text Message
     * @param xMsg
     * @return String
     */
    private String getTextMessage(XMessage xMsg) {
    	XMessagePayload payload = xMsg.getPayload();
    	String text = payload.getText().replace("__", "");
    	text = text.replace("\n\n", "");
//    	text = text.replaceAll("\n", "<br>");
    	text = text.replaceAll("\\\\n", "\n");
    	payload.setText(text);
    	return text;
    }
    
    /**
     * Get Button Choices with calculated keys
     * @param xMsg
     * @return ArrayList of ButtonChoices
     */
    private ArrayList<ButtonChoice> getButtonChoices(XMessage xMsg) {
    	String goBackText = "Go Back";
        String goToMainMenuText = "Main Menu";
        
    	ArrayList<ButtonChoice> choices = xMsg.getPayload().getButtonChoices();
    	setAssesmentCharacters();
    	if(choices == null) 
    		choices = new ArrayList();
    	
    	choices.forEach(c -> {
    		String[] a = c.getText().split(" ");
    		if(a[0] != null && !a[0].isEmpty()) {
    			String key = a[0].toString();
    			a = Arrays.copyOfRange(a, 1, a.length);
    			String text = String.join(" ", a);
    			
    			log.info("text: "+text);
    			c.setKey(key);
    			c.setText(text.trim());
    			if(c.getBackmenu() == null || c.getBackmenu() != true) {
    				c.setBackmenu(false);
    			}
    		}
    	});
    	xMsg.getPayload().setButtonChoices(choices);
    	
    	return choices;
    }

    private String renderMessageChoices(ArrayList<ButtonChoice> buttonChoices) {
        StringBuilder processedChoicesBuilder = new StringBuilder("");
        if(buttonChoices != null){
            for(ButtonChoice choice:buttonChoices){
                processedChoicesBuilder.append(choice.getText()).append("\n");
            }
            String processedChoices = processedChoicesBuilder.toString();
            return processedChoices.substring(0,processedChoices.length()-1);
        }
        return "";
    }

    /* Set Assesment Characters in variables */
    public void setAssesmentCharacters() {
    	String envAssesOneLevelUpChar = System.getenv("ASSESSMENT_ONE_LEVEL_UP_CHAR");
        String envAssesGoToStartChar = System.getenv("ASSESSMENT_GO_TO_START_CHAR");
        
        this.assesOneLevelUpChar = envAssesOneLevelUpChar == "0" || (envAssesOneLevelUpChar != null && !envAssesOneLevelUpChar.isEmpty()) ? envAssesOneLevelUpChar : "#";
        this.assesGoToStartChar = envAssesGoToStartChar == "0" || (envAssesGoToStartChar != null && !envAssesGoToStartChar.isEmpty()) ? envAssesGoToStartChar : "*";
    }
}

