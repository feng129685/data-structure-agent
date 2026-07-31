package com.feng.dsagent.animation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnimationConfiguration {

    @Bean
    AnimationValidator animationValidator() {
        return new AnimationValidator();
    }
}
