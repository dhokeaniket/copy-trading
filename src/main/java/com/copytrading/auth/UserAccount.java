package com.copytrading.auth;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("users")
public class UserAccount {
  @Id
  private Long id;
  @Column("username")
  private String username;
  @Column("password_hash")
  private String passwordHash;
  @Column("role")
  private Role role;
  @Column("active")
  private boolean active;

  public Long getId() {
    return id;
  }
  public void setId(Long id) {
    this.id = id;
  }
  public String getUsername() {
    return username;
  }
  public void setUsername(String username) {
    this.username = username;
  }
  public String getPasswordHash() {
    return passwordHash;
  }
  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }
  public Role getRole() {
    return role;
  }
  public void setRole(Role role) {
    this.role = role;
  }
  public boolean isActive() {
    return active;
  }
  public void setActive(boolean active) {
    this.active = active;
  }
}
