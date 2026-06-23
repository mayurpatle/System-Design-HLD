package org.example.behavioralPattern.Observer.NotificationFeature.observer;

public interface StockNotificationObserver {

    void  update();

    String getNotificationMethod();

    String  getUserId() ;

}
