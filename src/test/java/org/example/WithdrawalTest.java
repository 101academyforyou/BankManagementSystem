package org.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WithdrawalTest {
    private Main.Bank bank;
    private String accountId;

    @BeforeEach
    void setUp() {
        bank = new Main.Bank();
        Main.Customer customer = bank.addCustomer("測試客戶", "0912345678", "test@example.com");
        accountId = bank.openAccount(customer.id()).id;
        bank.deposit(accountId, new BigDecimal("10000"));
    }

    @Test
    void withdrawsSuccessfullyWhenBalanceIsSufficient() {
        Main.Transaction transaction = bank.withdraw(accountId, new BigDecimal("3000"));

        assertEquals("WITHDRAWAL", transaction.type());
        assertEquals(0, new BigDecimal("7000").compareTo(bank.balance(accountId)));
    }

    @Test
    void rejectsWithdrawalGreaterThanBalanceWithoutChangingBalance() {
        assertThrows(IllegalStateException.class,
                () -> bank.withdraw(accountId, new BigDecimal("15000")));

        assertEquals(0, new BigDecimal("10000").compareTo(bank.balance(accountId)));
    }

    @Test
    void allowsWithdrawalOfEntireBalance() {
        Main.Transaction transaction = bank.withdraw(accountId, new BigDecimal("10000"));

        assertEquals("WITHDRAWAL", transaction.type());
        assertEquals(0, BigDecimal.ZERO.compareTo(bank.balance(accountId)));
    }
}
