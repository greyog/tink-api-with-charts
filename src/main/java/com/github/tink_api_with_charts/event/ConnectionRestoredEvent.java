package com.github.tink_api_with_charts.event;

import org.springframework.context.ApplicationEvent;

public class ConnectionRestoredEvent extends ApplicationEvent {

    public ConnectionRestoredEvent(Object source) {
        super(source);
    }

}