package dh_sqlite;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

public final class JDBC implements Driver {
    private static final String PREFIX = "jdbc:dh_sqlite:";
    private final org.sqlite.JDBC delegate = new org.sqlite.JDBC();

    static {
        try {
            DriverManager.registerDriver(new JDBC());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) return null;
        return delegate.connect("jdbc:sqlite:" + url.substring(PREFIX.length()), info);
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return delegate.getPropertyInfo("jdbc:sqlite:" + url.substring(PREFIX.length()), info);
    }

    @Override public int getMajorVersion() { return delegate.getMajorVersion(); }
    @Override public int getMinorVersion() { return delegate.getMinorVersion(); }
    @Override public boolean jdbcCompliant() { return delegate.jdbcCompliant(); }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }
}
