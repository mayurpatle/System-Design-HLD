package org.example.behavioralPattern.Strategy.PaymentMethods.Problem;

public class PaymentProcessor {

    public void processPayment(String type , double  amt    ){
        switch(type) {
            case "credit card" -> {
                System.out.println("Paid Amount using " + amt +  " credit card");

            }
            case "paypal" -> {
                System.out.println("paid " + amt + " using paypal");

            }
            case "net_banking" -> {
                System.out.println("paid " + amt + " using net banking");

            }
            case "cash" -> {
                System.out.println("paid " + amt + " using cash");

            }
            default -> throw new IllegalArgumentException("Unexpetced value " + type ) ;

        }
    }
}
