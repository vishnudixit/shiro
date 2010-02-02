package org.apache.shiro.sample.bank;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import org.apache.shiro.sample.bank.AccountTransaction.TransactionType;
import org.apache.shiro.subject.Subject;

public class SecureBankService implements BankService {

  private Logger _logger;
  private boolean _isRunning;
  private List<Account> _accounts;
  private Map<Long,Account> _accountsById;
  
  /**
   * Creates a new {@link SecureBankService} instance.
   */
  public SecureBankService() {
    _logger = Logger.getLogger(SecureBankService.class);
    _accounts = new ArrayList<Account>();
    _accountsById = new HashMap<Long, Account>();
  }

  /**
   * Starts this service
   */
  public void start() throws Exception {
    _isRunning = true;
    _logger.info("Bank service started");
  }

  /**
   * Stop this service
   */
  public void dispose() {
    _logger.info("Stopping bank service...");
    _isRunning = false;
    
    synchronized (_accounts) {
      _accountsById.clear();
      _accounts.clear();
    }
    
    _logger.info("Bank service stopped");
  }

  /**
   * Internal utility method that validate the internal state of this service.
   */
  protected void assertServiceState() {
    if (!_isRunning) {
      throw new IllegalStateException("This bank service is not running");
    }
  }
  
  public int getAccountCount() {
    return _accounts.size();
  }
  
  /* (non-Javadoc)
   * @see com.connectif.trilogy.root.security.BankService#createNewAccount(java.lang.String)
   */
  @RequiresPermissions("bank:createAccount")
  public long createNewAccount(String anOwnerName) {
    assertServiceState();
    _logger.info("Creating new account for " + anOwnerName);
    
    synchronized (_accounts) {
      Account account = new Account(anOwnerName);
      account.setCreatedBy(getCurrentUsername());
      _accounts.add(account);
      _accountsById.put(account.getId(), account);

      _logger.debug("Created new account: " + account);
      return account.getId();
    }
  }

  /* (non-Javadoc)
   * @see com.connectif.trilogy.root.security.BankService#searchAccountIdsByOwner(java.lang.String)
   */
  public long[] searchAccountIdsByOwner(String anOwnerName) {
    assertServiceState();
    _logger.info("Searching existing accounts for " + anOwnerName);
    
    ArrayList<Account> matchAccounts = new ArrayList<Account>();
    synchronized (_accounts) {
      for (Account a: _accounts) {
        if (a.getOwnerName().toLowerCase().contains(anOwnerName.toLowerCase())) {
          matchAccounts.add(a);
        }
      }
    }
    
    long[] accountIds = new long[matchAccounts.size()];
    int index = 0;
    for(Account a: matchAccounts) {
      accountIds[index++] = a.getId();
    }
    
    _logger.debug("Found " + accountIds.length + " account(s) matching the name " + anOwnerName);
    return accountIds;
  }

  /* (non-Javadoc)
   * @see com.connectif.trilogy.root.security.BankService#getOwnerOf(long)
   */
  @RequiresPermissions("bank:readAccount")
  public String getOwnerOf(long anAccountId) throws AccountNotFoundException {
    assertServiceState();
    _logger.info("Getting owner of account " + anAccountId);
    
    Account a = safellyRetrieveAccountForId(anAccountId);
    return a.getOwnerName();
  }

  /* (non-Javadoc)
   * @see com.connectif.trilogy.root.security.BankService#getBalanceOf(long)
   */
  @RequiresPermissions("bank:readAccount")
  public double getBalanceOf(long anAccountId) throws AccountNotFoundException {
    assertServiceState();
    _logger.info("Getting balance of account " + anAccountId);
    
    Account a = safellyRetrieveAccountForId(anAccountId);
    return a.getBalance();
  }

  /* (non-Javadoc)
   * @see com.connectif.trilogy.root.security.BankService#depositInto(long, double)
   */
  @RequiresPermissions("bank:operateAccount")
  public double depositInto(long anAccountId, double anAmount) throws AccountNotFoundException, InactiveAccountException {
    assertServiceState();
    _logger.info("Making deposit of " + anAmount + " into account " + anAccountId);
    
    try {
      Account a = safellyRetrieveAccountForId(anAccountId);
      AccountTransaction tx = AccountTransaction.createDepositTx(anAccountId, anAmount);
      tx.setCreatedBy(getCurrentUsername());
      _logger.debug("Created a new transaction " + tx);

      a.applyTransaction(tx);
      _logger.debug("New balance of account " + a.getId() + " after deposit is " + a.getBalance());

      return a.getBalance();
      
    } catch (NotEnoughFundsException nefe) {
      throw new IllegalStateException("Should never happen", nefe);
    }
  }

  /* (non-Javadoc)
   * @see com.connectif.trilogy.root.security.BankService#withdrawFrom(long, double)
   */
  @RequiresPermissions("bank:operateAccount")
  public double withdrawFrom(long anAccountId, double anAmount) throws AccountNotFoundException, NotEnoughFundsException, InactiveAccountException {
    assertServiceState();
    _logger.info("Making withdrawal of " + anAmount + " from account " + anAccountId);
    
    Account a = safellyRetrieveAccountForId(anAccountId);
    AccountTransaction tx = AccountTransaction.createWithdrawalTx(anAccountId, anAmount);
    tx.setCreatedBy(getCurrentUsername());
    _logger.debug("Created a new transaction " + tx);
    
    a.applyTransaction(tx);
    _logger.debug("New balance of account " + a.getId() + " after withdrawal is " + a.getBalance());

    return a.getBalance();
  }

  /* (non-Javadoc)
   * @see com.connectif.trilogy.root.security.BankService#getTxHistoryFor(long)
   */
  @RequiresPermissions("bank:readAccount")
  public TxLog[] getTxHistoryFor(long anAccountId) throws AccountNotFoundException {
    assertServiceState();
    _logger.info("Getting transactions of account " + anAccountId);
    
    Account a = safellyRetrieveAccountForId(anAccountId);
    
    TxLog[] txs = new TxLog[a.getTransactions().size()];
    int index = 0;
    for (AccountTransaction tx: a.getTransactions()) {
      _logger.debug("Retrieved transaction " + tx);

      if (TransactionType.DEPOSIT == tx.getType()) {
        txs[index++] = new TxLog(tx.getCreationDate(), tx.getAmount(), tx.getCreatedBy());
      } else {
        txs[index++] = new TxLog(tx.getCreationDate(), -1.0d * tx.getAmount(), tx.getCreatedBy());
      }
    }
    
    return txs;
  }

  /* (non-Javadoc)
   * @see com.connectif.trilogy.root.security.BankService#closeAccount(long)
   */
  @RequiresPermissions("bank:closeAccount")
  public double closeAccount(long anAccountId) throws AccountNotFoundException, InactiveAccountException {
    assertServiceState();
    _logger.info("Closing account " + anAccountId);

    Account a = safellyRetrieveAccountForId(anAccountId);
    if (!a.isActive()) {
      throw new InactiveAccountException("The account " + anAccountId + " is already closed");
    }
    
    try {
      AccountTransaction tx = AccountTransaction.createWithdrawalTx(a.getId(), a.getBalance());
      tx.setCreatedBy(getCurrentUsername());
      _logger.debug("Created a new transaction " + tx);
      a.applyTransaction(tx);
      a.setActive(false);

      _logger.debug("Account " + a.getId() + " is now closed and an amount of " + tx.getAmount() + " is given to the owner");
      return tx.getAmount();
      
    } catch (NotEnoughFundsException nefe) {
      throw new IllegalStateException("Should never happen", nefe);
    }
  }

  /* (non-Javadoc)
   * @see com.connectif.trilogy.root.security.BankService#isAccountActive(long)
   */
  @RequiresPermissions("bank:readAccount")
  public boolean isAccountActive(long anAccountId) throws AccountNotFoundException {
    assertServiceState();
    _logger.info("Getting active status of account " + anAccountId);

    Account a = safellyRetrieveAccountForId(anAccountId);
    return a.isActive();
  }

  
  /**
   * Internal method that safelly (concurrency-wise) retrieves an account from the id passed in.
   * 
   * @param anAccountId The identifier of the account to retrieve.
   * @return The account instance retrieved.
   * @throws AccountNotFoundException If no account is found for the provided identifier.
   */
  protected Account safellyRetrieveAccountForId(long anAccountId) throws AccountNotFoundException {
    Account account = null;
    synchronized (_accounts) {
      account = _accountsById.get(anAccountId);
    }
    
    if (account == null) {
      throw new AccountNotFoundException("No account found for the id " + anAccountId);
    }
    
    _logger.info("Retrieved account " + account);
    return account;
  }
  
  /**
   * Internal utility method to retrieve the username of the current authenticated user.
   * 
   * @return The name.
   */
  protected String getCurrentUsername() {
    Subject subject = SecurityUtils.getSubject();
    if (subject == null || subject.getPrincipal() == null || !subject.isAuthenticated()) {
      throw new IllegalStateException("Unable to retrieve the current authenticated subject");
    }
    return SecurityUtils.getSubject().getPrincipal().toString();
  }
}