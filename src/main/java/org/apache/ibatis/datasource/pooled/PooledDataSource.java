/*
 *    Copyright 2009-2021 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.datasource.pooled;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

/**
 * This is a simple, synchronous, thread-safe database connection pool.
 * 数据路连接池
 * 1、 连接池使用的一些特殊场景描述
 * <p>
 * 如果连接池中维护的总连接数达到上限，且所有连接都已经被调用方占用，则后续获取数据库连接的线程将会被阻塞（进入阻塞队列中等待），直至连接池中出现可用的数据库连接，这个可用的连接是由其他使用方释放得到的。
 * <p>
 * 如果连接池中空闲连接数达到了配置上限，则后续返回到池中的空闲连接不会进入连接池缓存，而是直接关闭释放掉，这主要是为了减少维护空闲数据库连接带来的压力，同时减少数据库的资源开销。
 * <p>
 * 如果将连接总数的上限值设置得过大，可能会导致数据库因连接过多而僵死或崩溃，影响整个服务的可用性；而如果设置得过小，可能会无法完全发挥出数据库的性能，造成数据库资源的浪费。
 * <p>
 * 如果将空闲连接数的上限值设置得过大，可能会造成服务资源以及数据库资源的浪费，毕竟要维护这些空闲连接；如果设置得过小，当出现瞬间峰值请求时，服务的响应速度就会比较慢。
 * <p>
 * 2、PooledDataSource 核心方法  获取连接、释放连接、检测连接是否可用
 *
 * @author Clinton Begin
 */
public class PooledDataSource implements DataSource {

  private static final Log log = LogFactory.getLog(PooledDataSource.class);

  //使用PoolState管理连接。维护的不是Java.sql.Connection，而是PooledConnection对象
  private final PoolState state = new PoolState(this);

  private final UnpooledDataSource dataSource;

  // OPTIONAL CONFIGURATION FIELDS
  protected int poolMaximumActiveConnections = 10;
  protected int poolMaximumIdleConnections = 5;
  protected int poolMaximumCheckoutTime = 20000;
  protected int poolTimeToWait = 20000;
  protected int poolMaximumLocalBadConnectionTolerance = 3;
  protected String poolPingQuery = "NO PING QUERY SET";
  protected boolean poolPingEnabled;
  protected int poolPingConnectionsNotUsedFor;

  private int expectedConnectionTypeCode;

  public PooledDataSource() {
    dataSource = new UnpooledDataSource();
  }

  public PooledDataSource(UnpooledDataSource dataSource) {
    this.dataSource = dataSource;
  }

  public PooledDataSource(String driver, String url, String username, String password) {
    dataSource = new UnpooledDataSource(driver, url, username, password);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
  }

  public PooledDataSource(String driver, String url, Properties driverProperties) {
    dataSource = new UnpooledDataSource(driver, url, driverProperties);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
  }

  public PooledDataSource(ClassLoader driverClassLoader, String driver, String url, String username, String password) {
    dataSource = new UnpooledDataSource(driverClassLoader, driver, url, username, password);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
  }

  public PooledDataSource(ClassLoader driverClassLoader, String driver, String url, Properties driverProperties) {
    dataSource = new UnpooledDataSource(driverClassLoader, driver, url, driverProperties);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
  }

  @Override
  public Connection getConnection() throws SQLException {
    return popConnection(dataSource.getUsername(), dataSource.getPassword()).getProxyConnection();
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return popConnection(username, password).getProxyConnection();
  }

  @Override
  public void setLoginTimeout(int loginTimeout) {
    DriverManager.setLoginTimeout(loginTimeout);
  }

  @Override
  public int getLoginTimeout() {
    return DriverManager.getLoginTimeout();
  }

  @Override
  public void setLogWriter(PrintWriter logWriter) {
    DriverManager.setLogWriter(logWriter);
  }

  @Override
  public PrintWriter getLogWriter() {
    return DriverManager.getLogWriter();
  }

  public void setDriver(String driver) {
    dataSource.setDriver(driver);
    forceCloseAll();
  }

  public void setUrl(String url) {
    dataSource.setUrl(url);
    forceCloseAll();
  }

  public void setUsername(String username) {
    dataSource.setUsername(username);
    forceCloseAll();
  }

  public void setPassword(String password) {
    dataSource.setPassword(password);
    forceCloseAll();
  }

  public void setDefaultAutoCommit(boolean defaultAutoCommit) {
    dataSource.setAutoCommit(defaultAutoCommit);
    forceCloseAll();
  }

  public void setDefaultTransactionIsolationLevel(Integer defaultTransactionIsolationLevel) {
    dataSource.setDefaultTransactionIsolationLevel(defaultTransactionIsolationLevel);
    forceCloseAll();
  }

  public void setDriverProperties(Properties driverProps) {
    dataSource.setDriverProperties(driverProps);
    forceCloseAll();
  }

  /**
   * Sets the default network timeout value to wait for the database operation to complete. See {@link Connection#setNetworkTimeout(java.util.concurrent.Executor, int)}
   *
   * @param milliseconds The time in milliseconds to wait for the database operation to complete.
   * @since 3.5.2
   */
  public void setDefaultNetworkTimeout(Integer milliseconds) {
    dataSource.setDefaultNetworkTimeout(milliseconds);
    forceCloseAll();
  }

  /**
   * The maximum number of active connections.
   *
   * @param poolMaximumActiveConnections The maximum number of active connections
   */
  public void setPoolMaximumActiveConnections(int poolMaximumActiveConnections) {
    this.poolMaximumActiveConnections = poolMaximumActiveConnections;
    forceCloseAll();
  }

  /**
   * The maximum number of idle connections.
   *
   * @param poolMaximumIdleConnections The maximum number of idle connections
   */
  public void setPoolMaximumIdleConnections(int poolMaximumIdleConnections) {
    this.poolMaximumIdleConnections = poolMaximumIdleConnections;
    forceCloseAll();
  }

  /**
   * The maximum number of tolerance for bad connection happens in one thread
   * which are applying for new {@link PooledConnection}.
   *
   * @param poolMaximumLocalBadConnectionTolerance max tolerance for bad connection happens in one thread
   * @since 3.4.5
   */
  public void setPoolMaximumLocalBadConnectionTolerance(
    int poolMaximumLocalBadConnectionTolerance) {
    this.poolMaximumLocalBadConnectionTolerance = poolMaximumLocalBadConnectionTolerance;
  }

  /**
   * The maximum time a connection can be used before it *may* be
   * given away again.
   *
   * @param poolMaximumCheckoutTime The maximum time
   */
  public void setPoolMaximumCheckoutTime(int poolMaximumCheckoutTime) {
    this.poolMaximumCheckoutTime = poolMaximumCheckoutTime;
    forceCloseAll();
  }

  /**
   * The time to wait before retrying to get a connection.
   *
   * @param poolTimeToWait The time to wait
   */
  public void setPoolTimeToWait(int poolTimeToWait) {
    this.poolTimeToWait = poolTimeToWait;
    forceCloseAll();
  }

  /**
   * The query to be used to check a connection.
   *
   * @param poolPingQuery The query
   */
  public void setPoolPingQuery(String poolPingQuery) {
    this.poolPingQuery = poolPingQuery;
    forceCloseAll();
  }

  /**
   * Determines if the ping query should be used.
   *
   * @param poolPingEnabled True if we need to check a connection before using it
   */
  public void setPoolPingEnabled(boolean poolPingEnabled) {
    this.poolPingEnabled = poolPingEnabled;
    forceCloseAll();
  }

  /**
   * If a connection has not been used in this many milliseconds, ping the
   * database to make sure the connection is still good.
   *
   * @param milliseconds the number of milliseconds of inactivity that will trigger a ping
   */
  public void setPoolPingConnectionsNotUsedFor(int milliseconds) {
    this.poolPingConnectionsNotUsedFor = milliseconds;
    forceCloseAll();
  }

  public String getDriver() {
    return dataSource.getDriver();
  }

  public String getUrl() {
    return dataSource.getUrl();
  }

  public String getUsername() {
    return dataSource.getUsername();
  }

  public String getPassword() {
    return dataSource.getPassword();
  }

  public boolean isAutoCommit() {
    return dataSource.isAutoCommit();
  }

  public Integer getDefaultTransactionIsolationLevel() {
    return dataSource.getDefaultTransactionIsolationLevel();
  }

  public Properties getDriverProperties() {
    return dataSource.getDriverProperties();
  }

  /**
   * Gets the default network timeout.
   *
   * @return the default network timeout
   * @since 3.5.2
   */
  public Integer getDefaultNetworkTimeout() {
    return dataSource.getDefaultNetworkTimeout();
  }

  public int getPoolMaximumActiveConnections() {
    return poolMaximumActiveConnections;
  }

  public int getPoolMaximumIdleConnections() {
    return poolMaximumIdleConnections;
  }

  public int getPoolMaximumLocalBadConnectionTolerance() {
    return poolMaximumLocalBadConnectionTolerance;
  }

  public int getPoolMaximumCheckoutTime() {
    return poolMaximumCheckoutTime;
  }

  public int getPoolTimeToWait() {
    return poolTimeToWait;
  }

  public String getPoolPingQuery() {
    return poolPingQuery;
  }

  public boolean isPoolPingEnabled() {
    return poolPingEnabled;
  }

  public int getPoolPingConnectionsNotUsedFor() {
    return poolPingConnectionsNotUsedFor;
  }

  /**
   * Closes all active and idle connections in the pool.
   */
  public void forceCloseAll() {
    synchronized (state) {
      expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
      for (int i = state.activeConnections.size(); i > 0; i--) {
        try {
          PooledConnection conn = state.activeConnections.remove(i - 1);
          conn.invalidate();

          Connection realConn = conn.getRealConnection();
          if (!realConn.getAutoCommit()) {
            realConn.rollback();
          }
          realConn.close();
        } catch (Exception e) {
          // ignore
        }
      }
      for (int i = state.idleConnections.size(); i > 0; i--) {
        try {
          PooledConnection conn = state.idleConnections.remove(i - 1);
          conn.invalidate();

          Connection realConn = conn.getRealConnection();
          if (!realConn.getAutoCommit()) {
            realConn.rollback();
          }
          realConn.close();
        } catch (Exception e) {
          // ignore
        }
      }
    }
    if (log.isDebugEnabled()) {
      log.debug("PooledDataSource forcefully closed/removed all connections.");
    }
  }

  public PoolState getPoolState() {
    return state;
  }

  private int assembleConnectionTypeCode(String url, String username, String password) {
    return ("" + url + username + password).hashCode();
  }

  /**
   * 释放连接
   * <p>
   * 从活跃连接集合（即前面提到的 activeConnections 集合）中删除传入的 PooledConnection 对象。
   * <p>
   * 检测该 PooledConnection 对象是否可用。如果连接已不可用，则递增 badConnectionCount 字段进行统计，之后，直接丢弃 PooledConnection 对象即可。如果连接依旧可用，则执行下一步。
   * <p>
   * 检测当前 PooledDataSource 连接池中的空闲连接是否已经达到上限值。如果达到上限值，则 PooledConnection 无法放回到池中，正常关闭其底层的数据库连接即可。如果未达到上限值，则继续执行下一步。
   * <p>
   * 将底层连接重新封装成 PooledConnection 对象，并添加到空闲连接集合（也就是前面提到的 idleConnections 集合），然后唤醒所有阻塞等待空闲连接的线程。
   *
   * @param conn
   * @throws SQLException
   */
  protected void pushConnection(PooledConnection conn) throws SQLException {

    synchronized (state) {
      //步骤一：从活跃连接池集合中删除该连接
      state.activeConnections.remove(conn);
      //步骤二：检测PooledConnection对象是否可用
      if (conn.isValid()) {
        // 步骤3：检测当前PooledDataSource连接池中的空闲连接是否已经达到上限值
        if (state.idleConnections.size() < poolMaximumIdleConnections && conn.getConnectionTypeCode() == expectedConnectionTypeCode) {
          // 累计增加accumulatedCheckoutTime
          state.accumulatedCheckoutTime += conn.getCheckoutTime();
          if (!conn.getRealConnection().getAutoCommit()) {
            // 回滚未提交的事务
            conn.getRealConnection().rollback();
          }
          // 步骤4：将底层连接重新封装成PooledConnection对象，
          // 并添加到空闲连接集合（也就是前面提到的 idleConnections 集合）
          PooledConnection newConn = new PooledConnection(conn.getRealConnection(), this);
          state.idleConnections.add(newConn);
          // 设置新PooledConnection对象的创建时间戳和最后使用时间戳
          newConn.setCreatedTimestamp(conn.getCreatedTimestamp());
          newConn.setLastUsedTimestamp(conn.getLastUsedTimestamp());
          conn.invalidate();
          if (log.isDebugEnabled()) {
            log.debug("Returned connection " + newConn.getRealHashCode() + " to pool.");
          }
          // 唤醒所有阻塞等待空闲连接的线程
          state.notifyAll();
        } else {
          // 当前PooledDataSource连接池中的空闲连接已经达到上限值
          // 当前数据库连接无法放回到池中

          // 累计增加accumulatedCheckoutTime
          state.accumulatedCheckoutTime += conn.getCheckoutTime();
          if (!conn.getRealConnection().getAutoCommit()) {
            // 回滚未提交的事务
            conn.getRealConnection().rollback();
          }
          // 关闭真正的数据库连接
          conn.getRealConnection().close();
          if (log.isDebugEnabled()) {
            log.debug("Closed connection " + conn.getRealHashCode() + ".");
          }
          // 将PooledConnection对象设置为无效
          conn.invalidate();
        }
      } else {
        if (log.isDebugEnabled()) {
          log.debug("A bad connection (" + conn.getRealHashCode() + ") attempted to return to the pool, discarding connection.");
        }
        // 统计无效PooledConnection对象个数 之后直接丢弃
        state.badConnectionCount++;
      }
    }
  }

  /**
   * 获取数据库连接方法的核心实现
   * <p>
   * 检测当前连接池中是否有空闲的有效连接，如果有，则直接返回连接；如果没有，则继续执行下一步。
   * <p>
   * 检查连接池当前的活跃连接数是否已经达到上限值，如果未达到，则尝试创建一个新的数据库连接，并在创建成功之后，返回新建的连接；如果已达到最大上限，则往下执行。
   * <p>
   * 检查活跃连接中是否有连接超时，如果有，则将超时的连接从活跃连接集合中移除，并重复步骤 2；如果没有，则执行下一步。
   * <p>
   * 当前请求数据库连接的线程阻塞等待，并定期执行前面三步检测相应的分支是否可能获取连接。
   *
   * @param username
   * @param password
   * @return
   * @throws SQLException
   */
  private PooledConnection popConnection(String username, String password) throws SQLException {
    boolean countedWait = false;
    PooledConnection conn = null;
    long t = System.currentTimeMillis();
    int localBadConnectionCount = 0;

    while (conn == null) {
      //枷锁同步
      synchronized (state) {
        // 步骤1：检测空闲连接集合
        if (!state.idleConnections.isEmpty()) {
          // 获取空闲连接
          conn = state.idleConnections.remove(0);
          if (log.isDebugEnabled()) {
            log.debug("Checked out connection " + conn.getRealHashCode() + " from pool.");
          }
          // 没有空闲连接
        } else {
          // 步骤2：活跃连接数没有到上限值，则创建新连接
          if (state.activeConnections.size() < poolMaximumActiveConnections) {
            // 创建新数据库连接，并封装成PooledConnection对象
            conn = new PooledConnection(dataSource.getConnection(), this);
            if (log.isDebugEnabled()) {
              log.debug("Created connection " + conn.getRealHashCode() + ".");
            }
            // 活跃连接数已到上限值，则无法创建新连接
          } else {
            // Cannot create new connection
            // 步骤3：检测超时连接
            // 获取最早的活跃连接
            PooledConnection oldestActiveConnection = state.activeConnections.get(0);
            long longestCheckoutTime = oldestActiveConnection.getCheckoutTime();
            // 检测该连接是否超时
            if (longestCheckoutTime > poolMaximumCheckoutTime) {
              // Can claim overdue connection
              // 对超时连接的信息进行统计
              state.claimedOverdueConnectionCount++;
              state.accumulatedCheckoutTimeOfOverdueConnections += longestCheckoutTime;
              state.accumulatedCheckoutTime += longestCheckoutTime;
              // 将超时连接移出activeConnections集合
              state.activeConnections.remove(oldestActiveConnection);
              // 如果超时连接上有未提交的事务，则自动回滚
              if (!oldestActiveConnection.getRealConnection().getAutoCommit()) {
                try {
                  oldestActiveConnection.getRealConnection().rollback();
                } catch (SQLException e) {
                  /*
                     Just log a message for debug and continue to execute the following
                     statement like nothing happened.
                     Wrap the bad connection with a new PooledConnection, this will help
                     to not interrupt current executing thread and give current thread a
                     chance to join the next competition for another valid/good database
                     connection. At the end of this loop, bad {@link @conn} will be set as null.
                   */
                  log.debug("Bad connection. Could not roll back");
                }
              }
              // 创建新PooledConnection对象，但是真正的数据库连接
              conn = new PooledConnection(oldestActiveConnection.getRealConnection(), this);
              conn.setCreatedTimestamp(oldestActiveConnection.getCreatedTimestamp());
              conn.setLastUsedTimestamp(oldestActiveConnection.getLastUsedTimestamp());
              // 将超时PooledConnection设置为无效
              oldestActiveConnection.invalidate();
              if (log.isDebugEnabled()) {
                log.debug("Claimed overdue connection " + conn.getRealHashCode() + ".");
              }
            } else {
              // Must wait
              try {
                // 步骤4：无空闲连接、无法创建新连接且无超时连接，则只能阻塞等待
                if (!countedWait) {
                  // 统计阻塞等待次数
                  state.hadToWaitCount++;
                  countedWait = true;
                }
                if (log.isDebugEnabled()) {
                  log.debug("Waiting as long as " + poolTimeToWait + " milliseconds for connection.");
                }
                long wt = System.currentTimeMillis();
                // 阻塞等待
                state.wait(poolTimeToWait);
                // 统计累积的等待时间
                state.accumulatedWaitTime += System.currentTimeMillis() - wt;
              } catch (InterruptedException e) {
                break;
              }
            }
          }
        }
        // 对连接进行统计

        if (conn != null) {
          // ping to server and check the connection is valid or not
          // 检测PooledConnection是否有效
          if (conn.isValid()) {
            if (!conn.getRealConnection().getAutoCommit()) {
              conn.getRealConnection().rollback();
            }
            // 配置PooledConnection的相关属性，设置connectionTypeCode、checkoutTimestamp、lastUsedTimestamp字段的值
            conn.setConnectionTypeCode(assembleConnectionTypeCode(dataSource.getUrl(), username, password));
            conn.setCheckoutTimestamp(System.currentTimeMillis());
            conn.setLastUsedTimestamp(System.currentTimeMillis());
            state.activeConnections.add(conn);
            state.requestCount++;
            state.accumulatedRequestTime += System.currentTimeMillis() - t;
          } else {// 统计失败的情况
            if (log.isDebugEnabled()) {
              log.debug("A bad connection (" + conn.getRealHashCode() + ") was returned from the pool, getting another connection.");
            }
            state.badConnectionCount++;
            localBadConnectionCount++;
            conn = null;
            if (localBadConnectionCount > (poolMaximumIdleConnections + poolMaximumLocalBadConnectionTolerance)) {
              if (log.isDebugEnabled()) {
                log.debug("PooledDataSource: Could not get a good connection to the database.");
              }
              throw new SQLException("PooledDataSource: Could not get a good connection to the database.");
            }
          }
        }
      }

    }

    if (conn == null) {
      if (log.isDebugEnabled()) {
        log.debug("PooledDataSource: Unknown severe error condition.  The connection pool returned a null connection.");
      }
      throw new SQLException("PooledDataSource: Unknown severe error condition.  The connection pool returned a null connection.");
    }

    return conn;
  }

  /**
   * Method to check to see if a connection is still usable
   * 检测连接是否可用，具体检测下面三点，同时满足则该PooleConnection对象可用
   * <p>
   * valid 字段值为 true；
   * <p>
   * realConnection 字段值不为空；
   * <p>
   * 执行 PooledDataSource.pingConnection() 方法，返回值为 true。
   *
   * @param conn - the connection to check
   * @return True if the connection is still usable
   */
  protected boolean pingConnection(PooledConnection conn) {
    // 记录此次ping操作是否成功完成
    boolean result = true;

    try {
      // 检测底层数据库连接是否已经关闭
      result = !conn.getRealConnection().isClosed();
    } catch (SQLException e) {
      if (log.isDebugEnabled()) {
        log.debug("Connection " + conn.getRealHashCode() + " is BAD: " + e.getMessage());
      }
      result = false;
    }

    // 如果底层与数据库的网络连接没断开，则需要检测poolPingEnabled字段的配置，决定
    // 是否能执行ping操作。另外，ping操作不能频繁执行，只有超过一定时长
    // (超过poolPingConnectionsNotUsedFor指定的时长)未使用的连接，才需要ping
    // 操作来检测数据库连接是否正常
    if (result && poolPingEnabled && poolPingConnectionsNotUsedFor >= 0
      && conn.getTimeElapsedSinceLastUse() > poolPingConnectionsNotUsedFor) {
      try {
        if (log.isDebugEnabled()) {
          log.debug("Testing connection " + conn.getRealHashCode() + " ...");
        }
        // 执行poolPingQuery字段中记录的测试SQL语句
        Connection realConn = conn.getRealConnection();
        try (Statement statement = realConn.createStatement()) {
          statement.executeQuery(poolPingQuery).close();
        }
        if (!realConn.getAutoCommit()) {
          realConn.rollback();
        }
        // 不抛异常，即为成功
        result = true;
        if (log.isDebugEnabled()) {
          log.debug("Connection " + conn.getRealHashCode() + " is GOOD!");
        }
      } catch (Exception e) {
        log.warn("Execution of ping query '" + poolPingQuery + "' failed: " + e.getMessage());
        try {
          conn.getRealConnection().close();
        } catch (Exception e2) {
          // ignore
        }
        // 抛异常，即为失败
        result = false;
        if (log.isDebugEnabled()) {
          log.debug("Connection " + conn.getRealHashCode() + " is BAD: " + e.getMessage());
        }
      }
    }
    return result;
  }

  /**
   * Unwraps a pooled connection to get to the 'real' connection
   *
   * @param conn - the pooled connection to unwrap
   * @return The 'real' connection
   */
  public static Connection unwrapConnection(Connection conn) {
    if (Proxy.isProxyClass(conn.getClass())) {
      InvocationHandler handler = Proxy.getInvocationHandler(conn);
      if (handler instanceof PooledConnection) {
        return ((PooledConnection) handler).getRealConnection();
      }
    }
    return conn;
  }

  @Override
  protected void finalize() throws Throwable {
    forceCloseAll();
    super.finalize();
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    throw new SQLException(getClass().getName() + " is not a wrapper.");
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) {
    return false;
  }

  @Override
  public Logger getParentLogger() {
    return Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
  }

}
