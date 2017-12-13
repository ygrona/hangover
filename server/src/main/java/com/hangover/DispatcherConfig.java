package com.hangover;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.support.Function;
import org.springframework.integration.handler.AbstractMessageHandler;
import org.springframework.integration.websocket.IntegrationWebSocketContainer;
import org.springframework.integration.websocket.ServerWebSocketContainer;
import org.springframework.integration.websocket.inbound.WebSocketInboundChannelAdapter;
import org.springframework.integration.websocket.outbound.WebSocketOutboundMessageHandler;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.ViewResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;
import org.springframework.web.servlet.view.InternalResourceViewResolver;
import org.springframework.web.servlet.view.JstlView;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.ServletWebSocketHandlerRegistry;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeFailureException;
import org.springframework.web.socket.server.HandshakeHandler;
import org.thymeleaf.spring3.SpringTemplateEngine;
import org.thymeleaf.spring3.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring3.view.ThymeleafView;
import org.thymeleaf.spring3.view.ThymeleafViewResolver;
import org.thymeleaf.templatemode.TemplateMode;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Configuration
@ComponentScan
@EnableWebMvc
@EnableIntegration
public class DispatcherConfig extends WebMvcConfigurerAdapter implements ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/resources/**").addResourceLocations("/resources/");
        super.addResourceHandlers(registry);
    }

    @Bean
    public SpringResourceTemplateResolver templateResolver(){
        // SpringResourceTemplateResolver automatically integrates with Spring's own
        // resource resolution infrastructure, which is highly recommended.
        SpringResourceTemplateResolver templateResolver = new SpringResourceTemplateResolver();
        templateResolver.setApplicationContext(this.applicationContext);
        templateResolver.setPrefix("/WEB-INF/views/");
        templateResolver.setSuffix(".html");
        // HTML is the default value, added here for the sake of clarity.
        templateResolver.setTemplateMode(TemplateMode.HTML);
        // Template cache is true by default. Set to false if you want
        // templates to be automatically updated when modified.
        templateResolver.setCacheable(true);
        return templateResolver;
    }

    @Bean
    public SpringTemplateEngine templateEngine(){
        // SpringTemplateEngine automatically applies SpringStandardDialect and
        // enables Spring's own MessageSource message resolution mechanisms.
        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(templateResolver());
        // Enabling the SpringEL compiler with Spring 4.2.4 or newer can
        // speed up execution in most scenarios, but might be incompatible
        // with specific cases when expressions in one template are reused
        // across different data types, so this flag is "false" by default
        // for safer backwards compatibility.
        //templateEngine.setEnableSpringELCompiler(true);
        return templateEngine;
    }

    @Bean
    public ThymeleafViewResolver viewResolver(){
        ThymeleafViewResolver viewResolver = new ThymeleafViewResolver();
        viewResolver.setTemplateEngine(templateEngine());
        // NOTE 'order' and 'viewNames' are optional
        viewResolver.setOrder(1);
        //viewResolver.setViewNames(new String[] {"*.html", "*.xhtml"});
        return viewResolver;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**");
    }

    @Bean()
    IntegrationWebSocketContainer serverWebSocketContainer() {
        ServerWebSocketContainer webSocketContainer = new ServerWebSocketContainer("/names").setAllowedOrigins("*");
                //.withSockJs();
        return webSocketContainer;
    }

    @Bean
    WebSocketInboundChannelAdapter webSocketInboundAdapter() {
        WebSocketInboundChannelAdapter webSocketInboundChannelAdapter = new WebSocketInboundChannelAdapter(serverWebSocketContainer());
        webSocketInboundChannelAdapter.setOutputChannel(requestChannel());

        return webSocketInboundChannelAdapter;
    }

    @Bean
    MessageHandler webSocketOutboundAdapter() {
        WebSocketOutboundMessageHandler outboundMessageHandler =
                new WebSocketOutboundMessageHandler(serverWebSocketContainer());
        return outboundMessageHandler;
    }

    @Bean(name = "webSocketFlow.input")
    MessageChannel requestChannel() {
        return new DirectChannel();
    }

    @Bean
    @ServiceActivator(inputChannel = "webSocketFlow.input")
    public MessageHandler sendChatMessageHandler() {
        AbstractMessageHandler messageHandler = new AbstractMessageHandler() {
            {

            }
            @Override
            protected void handleMessageInternal(Message<?> message) throws Exception {
                IntegrationWebSocketContainer socketContainer = serverWebSocketContainer();
                String sessionId = message.getHeaders().get(SimpMessageHeaderAccessor.SESSION_ID_HEADER).toString();
                System.out.println("#####: " + socketContainer + " ("+sessionId+") : " + message.getPayload());
                Map<String, Object> newHeaders = new HashMap<>(message.getHeaders());
                socketContainer.getSessions().forEach((k,v)-> {
                    if (!k.equals(sessionId)) {
                        newHeaders.put(SimpMessageHeaderAccessor.SESSION_ID_HEADER, k);
                        Message out = MessageBuilder.withPayload("["+sessionId+"=>"+k+"]: " + message.getPayload())
                                .copyHeaders(newHeaders).build();

                        webSocketOutboundAdapter().handleMessage(out);
                    }
                });
            }
        };

        return messageHandler;
    }

//    @Bean
//    IntegrationFlow webSocketFlow() {
//        return f -> {
//            Function<Message, Object> splitter = m -> serverWebSocketContainer()
//                    .getSessions()
//                    .keySet()
//                    .stream()
//                    .map(s -> MessageBuilder.fromMessage(m)
//                            .setHeader(SimpMessageHeaderAccessor.SESSION_ID_HEADER, s)
//                            .build())
//                    .collect(Collectors.toList());
//            f.split( Message.class, splitter)
//                    .channel(c -> c.executor(Executors.newCachedThreadPool()))
//                    .handle(webSocketOutboundAdapter());
//        };
//    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
