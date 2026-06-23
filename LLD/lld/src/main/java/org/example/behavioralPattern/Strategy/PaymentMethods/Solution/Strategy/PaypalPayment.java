package org.example.behavioralPattern.Strategy.PaymentMethods.Solution.Strategy;

public class PaypalPayment implements PaymentStrategy{

    @Override
    public void pay(double amt) {
        System.out.println("paid "+ amt + " using paypal");

    }
}
