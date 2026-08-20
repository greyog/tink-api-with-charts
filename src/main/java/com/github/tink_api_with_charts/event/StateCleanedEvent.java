package com.github.tink_api_with_charts.event;

import org.springframework.context.ApplicationEvent;

public class StateCleanedEvent extends ApplicationEvent {

    public StateCleanedEvent(Object source) {
        super(source);
    }

}