package id.my.kadaferhidup.fraudguard.risk;

public enum TxnType {
    TRANSFER_OUT, TRANSFER_IN, CASH_DEPOSIT, CASH_WITHDRAWAL;

    public boolean outgoing() {
        return this == TRANSFER_OUT || this == CASH_WITHDRAWAL;
    }

    public boolean incoming() {
        return this == TRANSFER_IN || this == CASH_DEPOSIT;
    }

    public boolean cash() {
        return this == CASH_DEPOSIT || this == CASH_WITHDRAWAL;
    }

    public boolean transfer() {
        return this == TRANSFER_OUT || this == TRANSFER_IN;
    }
}
