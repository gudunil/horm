package com.holo.framework.horm.core.transaction;

import java.math.BigDecimal;

import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.meta.annotation.Column;
import com.holo.framework.horm.meta.annotation.Entity;
import com.holo.framework.horm.meta.annotation.GenerationType;
import com.holo.framework.horm.meta.annotation.Id;

/**
 * Integration-test entity for verifying transaction commit/rollback behavior
 * against an in-memory H2 database.
 */
@Entity(table = "accounts")
public class Account extends Model<Account> {

    @Id(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private BigDecimal balance;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }

}
