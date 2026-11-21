package id.my.kadaferhidup.fraudguard.risk;

/** Know-your-customer data the bank already holds. monthlyIncome is declared income, in sen. */
public record Customer(String id, String name, String label, long monthlyIncome, Rating rating) {

    public enum Rating { LOW, MEDIUM, HIGH }
}
