package org.example;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** A small, runnable bank back-office prototype. Data is held in memory. */
public class Main {
    private static final Scanner IN = new Scanner(System.in);
    private static final Bank BANK = new Bank();

    public static void main(String[] args) {
        System.out.println("=== 銀行後台管理系統 ===");
        while (true) {
            System.out.println("\n1 客戶管理  2 帳戶管理  3 交易  4 稽核紀錄  0 離開");
            switch (read("請選擇：")) {
                case "1" -> customers();
                case "2" -> accounts();
                case "3" -> transactions();
                case "4" -> audit();
                case "0" -> { System.out.println("系統已結束。"); return; }
                default -> System.out.println("無效選項。");
            }
        }
    }

    private static void customers() {
        while (true) {
            System.out.println("\n--- 客戶管理 ---\n1 新增  2 查詢  3 修改  4 刪除  0 返回");
            switch (read("請選擇：")) {
                case "1" -> { Customer c = BANK.addCustomer(read("姓名："), read("電話："), read("電子郵件：")); System.out.println("已新增，客戶編號：" + c.id); }
                case "2" -> { String q = read("輸入客戶編號或姓名："); BANK.findCustomers(q).forEach(System.out::println); }
                case "3" -> { String id = read("客戶編號："); System.out.println(BANK.updateCustomer(id, read("新姓名："), read("新電話："), read("新電子郵件："))); }
                case "4" -> System.out.println(BANK.deleteCustomer(read("客戶編號：")));
                case "0" -> { return; }
                default -> System.out.println("無效選項。");
            }
        }
    }

    private static void accounts() {
        while (true) {
            System.out.println("\n--- 帳戶管理 ---\n1 開立  2 查詢  3 查看餘額  4 停用  0 返回");
            switch (read("請選擇：")) {
                case "1" -> { Account a = BANK.openAccount(read("客戶編號：")); System.out.println("已開立，帳戶編號：" + a.id); }
                case "2" -> { String id = read("帳戶編號："); System.out.println(BANK.account(id)); }
                case "3" -> System.out.println("餘額：" + BANK.balance(read("帳戶編號：")));
                case "4" -> System.out.println(BANK.disableAccount(read("帳戶編號：")));
                case "0" -> { return; }
                default -> System.out.println("無效選項。");
            }
        }
    }

    private static void transactions() {
        while (true) {
            System.out.println("\n--- 交易 ---\n1 存款  2 提款  3 轉帳  4 查最近三個月  0 返回");
            try {
                switch (read("請選擇：")) {
                    case "1" -> System.out.println(BANK.deposit(read("帳戶編號："), amount("金額：")));
                    case "2" -> System.out.println(BANK.withdraw(read("帳戶編號："), amount("金額：")));
                    case "3" -> System.out.println(BANK.transfer(read("轉出帳戶："), read("轉入帳戶："), amount("金額：")));
                    case "4" -> BANK.recentTransactions(read("帳戶編號：")).forEach(System.out::println);
                    case "0" -> { return; }
                    default -> System.out.println("無效選項。");
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                System.out.println("操作失敗：" + e.getMessage());
            }
        }
    }

    private static void audit() {
        List<AuditRecord> records = BANK.auditRecords();
        records.forEach(System.out::println);
        System.out.println("雜湊鏈驗證：" + (BANK.verifyAuditChain() ? "通過" : "失敗") + "（" + records.size() + " 筆）");
    }

    private static BigDecimal amount(String prompt) {
        try { return new BigDecimal(read(prompt)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("金額格式錯誤。"); }
    }
    private static String read(String prompt) { System.out.print(prompt); return IN.nextLine().trim(); }

    static final class Bank {
        private final Map<String, Customer> customers = new LinkedHashMap<>();
        private final Map<String, Account> accounts = new LinkedHashMap<>();
        private final List<AuditRecord> audit = new ArrayList<>();
        // Per-account time indexes keep recent queries at O(log n + returned rows).
        private final Map<String, NavigableMap<Instant, List<Transaction>>> transactionsByAccount = new HashMap<>();
        private String lastAuditHash = "GENESIS";

        synchronized Customer addCustomer(String name, String phone, String email) {
            requireText(name, "姓名");
            Customer c = new Customer(id("C"), name, phone, email);
            customers.put(c.id, c);
            record("CUSTOMER_CREATED", c.id, "name=" + name);
            return c;
        }

        synchronized List<Customer> findCustomers(String query) {
            String q = query.toLowerCase(Locale.ROOT);
            return customers.values().stream().filter(c -> c.id.equalsIgnoreCase(query) || c.name.toLowerCase(Locale.ROOT).contains(q)).toList();
        }

        synchronized String updateCustomer(String id, String name, String phone, String email) {
            Customer old = customer(id);
            requireText(name, "姓名");
            customers.put(id, new Customer(id, name, phone, email));
            record("CUSTOMER_UPDATED", id, "profile updated");
            return "客戶資料已更新。";
        }

        synchronized String deleteCustomer(String id) {
            customer(id);
            if (accounts.values().stream().anyMatch(a -> a.customerId.equals(id))) throw new IllegalStateException("客戶仍有帳戶，無法刪除。");
            customers.remove(id);
            record("CUSTOMER_DELETED", id, "customer removed");
            return "客戶已刪除。";
        }

        synchronized Account openAccount(String customerId) {
            customer(customerId);
            Account a = new Account(id("A"), customerId, BigDecimal.ZERO, true);
            accounts.put(a.id, a);
            transactionsByAccount.put(a.id, new TreeMap<>());
            record("ACCOUNT_OPENED", a.id, "customer=" + customerId);
            return a;
        }

        synchronized String account(String id) { return requireAccount(id).toString(); }
        synchronized BigDecimal balance(String id) { return requireAccount(id).balance; }

        synchronized String disableAccount(String id) {
            Account a = requireAccount(id);
            if (a.balance.signum() != 0) throw new IllegalStateException("停用前必須先將餘額轉出或提領至零。");
            if (!a.active) throw new IllegalStateException("帳戶已停用。");
            a.active = false;
            record("ACCOUNT_DISABLED", id, "account disabled");
            return "帳戶已停用。";
        }

        synchronized Transaction deposit(String accountId, BigDecimal amount) {
            validateAmount(amount);
            Account a = requireActiveAccount(accountId);
            a.balance = a.balance.add(amount);
            return saveTransaction("DEPOSIT", accountId, null, amount);
        }

        synchronized Transaction withdraw(String accountId, BigDecimal amount) {
            validateAmount(amount);
            Account a = requireActiveAccount(accountId);
            if (a.balance.compareTo(amount) < 0) throw new IllegalStateException("餘額不足。");
            a.balance = a.balance.subtract(amount);
            return saveTransaction("WITHDRAWAL", accountId, null, amount);
        }

        synchronized Transaction transfer(String fromId, String toId, BigDecimal amount) {
            validateAmount(amount);
            if (fromId.equals(toId)) throw new IllegalArgumentException("轉出與轉入帳戶不可相同。");
            Account from = requireActiveAccount(fromId), to = requireActiveAccount(toId);
            if (from.balance.compareTo(amount) < 0) throw new IllegalStateException("轉出帳戶餘額不足。");
            from.balance = from.balance.subtract(amount);
            to.balance = to.balance.add(amount);
            Transaction t = new Transaction(id("T"), "TRANSFER", fromId, toId, amount, Instant.now());
            index(t, fromId); index(t, toId);
            record("TRANSFER", t.id, "from=" + fromId + ";to=" + toId + ";amount=" + amount.toPlainString());
            return t;
        }

        synchronized List<Transaction> recentTransactions(String accountId) {
            requireAccount(accountId);
            Instant now = Instant.now();
            Instant from = now.atZone(ZoneId.systemDefault()).minusMonths(3).toInstant();
            NavigableMap<Instant, List<Transaction>> index = transactionsByAccount.get(accountId);
            return index.subMap(from, true, now, true).values().stream().flatMap(Collection::stream)
                    .sorted(Comparator.comparing(Transaction::time).reversed()).toList();
        }

        synchronized List<AuditRecord> auditRecords() { return List.copyOf(audit); }
        synchronized boolean verifyAuditChain() {
            String previous = "GENESIS";
            for (AuditRecord r : audit) {
                if (!r.previousHash.equals(previous) || !r.hash.equals(hash(r.payload(previous)))) return false;
                previous = r.hash;
            }
            return previous.equals(lastAuditHash);
        }

        private Transaction saveTransaction(String type, String accountId, String other, BigDecimal amount) {
            Transaction t = new Transaction(id("T"), type, accountId, other, amount, Instant.now());
            index(t, accountId);
            record(type, t.id, "account=" + accountId + ";amount=" + amount.toPlainString());
            return t;
        }
        private void index(Transaction t, String accountId) {
            transactionsByAccount.get(accountId).computeIfAbsent(t.time, ignored -> new ArrayList<>()).add(t);
        }
        private void record(String action, String subject, String details) {
            AuditRecord r = new AuditRecord(Instant.now(), action, subject, details, lastAuditHash, "");
            r = new AuditRecord(r.time, r.action, r.subject, r.details, r.previousHash, hash(r.payload(r.previousHash)));
            audit.add(r);
            lastAuditHash = r.hash;
        }
        private Customer customer(String id) {
            Customer c = customers.get(id);
            if (c == null) throw new IllegalArgumentException("找不到客戶：" + id);
            return c;
        }
        private Account requireAccount(String id) {
            Account a = accounts.get(id);
            if (a == null) throw new IllegalArgumentException("找不到帳戶：" + id);
            return a;
        }
        private Account requireActiveAccount(String id) {
            Account a = requireAccount(id);
            if (!a.active) throw new IllegalStateException("帳戶已停用。");
            return a;
        }
        private static void validateAmount(BigDecimal n) {
            if (n == null || n.signum() <= 0 || n.scale() > 2) throw new IllegalArgumentException("金額須為大於零且最多兩位小數。");
        }
    }

    record Customer(String id, String name, String phone, String email) {
        @Override public String toString() { return "客戶{" + id + ", 姓名='" + name + "', 電話='" + phone + "', Email='" + email + "'}"; }
    }
    static final class Account {
        final String id, customerId;
        BigDecimal balance;
        boolean active;
        Account(String id, String customerId, BigDecimal balance, boolean active) { this.id=id; this.customerId=customerId; this.balance=balance; this.active=active; }
        @Override public String toString() { return "帳戶{" + id + ", 客戶=" + customerId + ", 餘額=" + balance.toPlainString() + ", 狀態=" + (active ? "啟用" : "停用") + "}"; }
    }
    record Transaction(String id, String type, String accountId, String otherAccountId, BigDecimal amount, Instant time) {
        @Override public String toString() { return time + " " + type + " " + id + " 帳戶=" + accountId + (otherAccountId == null ? "" : " 對方=" + otherAccountId) + " 金額=" + amount.toPlainString(); }
    }
    record AuditRecord(Instant time, String action, String subject, String details, String previousHash, String hash) {
        String payload(String previous) { return time + "|" + action + "|" + subject + "|" + details + "|" + previous; }
        @Override public String toString() { return time + " " + action + " subject=" + subject + " details=" + details + " hash=" + hash; }
    }

    private static String id(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT); }
    private static void requireText(String s, String label) { if (s == null || s.isBlank()) throw new IllegalArgumentException(label + "不可空白。"); }
    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
