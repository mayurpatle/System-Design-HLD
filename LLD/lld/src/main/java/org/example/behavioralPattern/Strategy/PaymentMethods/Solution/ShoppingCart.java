package org.example.behavioralPattern.Strategy.PaymentMethods.Solution;


// context holder to the all strategies

import org.example.behavioralPattern.Strategy.PaymentMethods.Solution.Strategy.PaymentStrategy;

public class ShoppingCart {
    private PaymentStrategy paymentStrategy   ;

    public void setPaymentStrategy(PaymentStrategy strategy){
        this.paymentStrategy  = strategy  ;

    }
    public void   checkout(double amt ){
        System.out.println(this.paymentStrategy.getClass().getSimpleName() + ": ");
        paymentStrategy.pay(amt);

    }

}
