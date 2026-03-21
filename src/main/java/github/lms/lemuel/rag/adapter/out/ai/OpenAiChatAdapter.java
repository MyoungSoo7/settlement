package github.lms.lemuel.rag.adapter.out.ai;

import github.lms.lemuel.rag.application.port.out.ChatPort;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class OpenAiChatAdapter implements ChatPort {

    private final ChatModel chatModel;

    @Override
    public void streamChat(String systemPrompt, List<ChatMessage> chatMessages, Consumer<String> tokenConsumer) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));

        for (ChatMessage msg : chatMessages) {
            if ("USER".equalsIgnoreCase(msg.role())) {
                messages.add(new UserMessage(msg.content()));
            } else {
                messages.add(new AssistantMessage(msg.content()));
            }
        }

        Prompt prompt = new Prompt(messages);
        Flux<String> stream = chatModel.stream(prompt)
                .map(response -> {
                    if (response.getResult() != null && response.getResult().getOutput() != null) {
                        String text = response.getResult().getOutput().getText();
                        return text != null ? text : "";
                    }
                    return "";
                })
                .filter(text -> !text.isEmpty());

        stream.doOnNext(tokenConsumer).blockLast();
    }
}
