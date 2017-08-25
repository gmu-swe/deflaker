package org.deflaker.maven;


import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

public class SQLDriverHack implements Driver {
	private Driver delegate;
	public SQLDriverHack(Driver delegat)
	{
		this.delegate =delegat;
	}

	public Connection connect(String url, Properties info) throws SQLException {
		return delegate.connect(url, info);
	}

	public boolean acceptsURL(String url) throws SQLException {
		return delegate.acceptsURL(url);
	}

	public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
		return delegate.getPropertyInfo(url, info);
	}

	public int getMajorVersion() {
		return delegate.getMajorVersion();
	}

	public int getMinorVersion() {
		return delegate.getMinorVersion();
	}

	public boolean jdbcCompliant() {
		return delegate.jdbcCompliant();
	}

	public Logger getParentLogger() throws SQLFeatureNotSupportedException {
		return delegate.getParentLogger();
	}
	
}
