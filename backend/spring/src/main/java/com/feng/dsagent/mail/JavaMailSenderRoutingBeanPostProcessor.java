package com.feng.dsagent.mail;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

@Component
final class JavaMailSenderRoutingBeanPostProcessor implements BeanPostProcessor {

    private final SmtpEndpointRouting routing;

    JavaMailSenderRoutingBeanPostProcessor(SmtpEndpointRouting routing) {
        this.routing = routing;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof JavaMailSenderImpl sender) {
            routing.applyTo(sender, SmtpEndpointRouting.connectionTimeoutMillis(sender.getJavaMailProperties()));
        }
        return bean;
    }
}
