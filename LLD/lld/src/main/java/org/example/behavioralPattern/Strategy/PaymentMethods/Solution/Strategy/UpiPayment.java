package org.example.behavioralPattern.Strategy.PaymentMethods.Solution.Strategy;

public class UpiPayment implements PaymentStrategy{
    @Override
    public void pay(double amt) {
        System.out.println("Paid usig " + amt + " using UPI");

    }
}
