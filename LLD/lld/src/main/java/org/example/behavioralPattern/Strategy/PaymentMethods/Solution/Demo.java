package org.example.behavioralPattern.Strategy.PaymentMethods.Solution;

import org.example.behavioralPattern.Strategy.PaymentMethods.Solution.Strategy.CreditCardPayment;
import org.example.behavioralPattern.Strategy.PaymentMethods.Solution.Strategy.PaypalPayment;
import org.example.behavioralPattern.Strategy.PaymentMethods.Solution.Strategy.UpiPayment;

public class Demo {

    public static void main(String[] args) {
        System.out.println("Strategy Design Pattern");
        System.out.println("Example   : payment processor");

        ShoppingCart cart  =  new ShoppingCart();

        // Assinging directly the payment strategy at runtime
        cart.setPaymentStrategy(new CreditCardPayment());
        cart.checkout(1000.0);
        cart.setPaymentStrategy(new PaypalPayment());
        cart.checkout(145.00);
        cart.setPaymentStrategy(new UpiPayment());
        cart.checkout(140.00);

    }
}
