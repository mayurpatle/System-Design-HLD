package org.example.behavioralPattern.Strategy.PaymentMethods.Problem;

public class Demo {

    public static void main(String[] args) {
        System.out.println("Payment processor problem  demo  ");
        PaymentProcessor  processor = new PaymentProcessor();
        processor.processPayment("credit card" , 1000);
        processor.processPayment("cash" , 20000); ;
        processor.processPayment("paypal" , 3000);
        processor.processPayment("net_banking" , 5000);

    }
}
