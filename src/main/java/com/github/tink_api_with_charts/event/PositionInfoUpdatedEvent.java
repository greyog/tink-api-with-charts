package com.github.tink_api_with_charts.event;

import org.springframework.context.ApplicationEvent;

public class PositionInfoUpdatedEvent extends ApplicationEvent {

    public PositionInfoUpdatedEvent(Object source) {
        super(source);
    }

}