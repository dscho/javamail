/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.mail.util;

import java.security.*;
import java.net.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.*;
import java.util.logging.Level;
import java.security.cert.*;
import javax.net.*;
import javax.net.ssl.*;
import javax.security.auth.x500.X500Principal;

/**
 * This class is used to get Sockets. Depending on the arguments passed
 * it will either return a plain java.net.Socket or dynamically load
 * the SocketFactory class specified in the classname param and return
 * a socket created by that SocketFactory.
 *
 * @author Max Spivak
 * @author Bill Shannon
 */
public class SocketFetcher {

    private static MailLogger logger = new MailLogger(
	SocketFetcher.class,
	"socket",
	"DEBUG SocketFetcher",
	PropUtil.getBooleanSystemProperty("mail.socket.debug", false),
	System.out);

    // No one should instantiate this class.
    private SocketFetcher() {
    }

    /**
     * This method returns a Socket.  Properties control the use of
     * socket factories and other socket characteristics.  The properties
     * used are: <p>
     * <ul>
     * <li> <i>prefix</i>.socketFactory
     * <li> <i>prefix</i>.socketFactory.class
     * <li> <i>prefix</i>.socketFactory.fallback
     * <li> <i>prefix</i>.socketFactory.port
     * <li> <i>prefix</i>.ssl.socketFactory
     * <li> <i>prefix</i>.ssl.socketFactory.class
     * <li> <i>prefix</i>.ssl.socketFactory.port
     * <li> <i>prefix</i>.timeout
     * <li> <i>prefix</i>.connectiontimeout
     * <li> <i>prefix</i>.localaddress
     * <li> <i>prefix</i>.localport
     * </ul> <p>
     * If we're making an SSL connection, the ssl.socketFactory
     * properties are used first, if set. <p>
     *
     * If the socketFactory property is set, the value is an
     * instance of a SocketFactory class, not a string.  The
     * instance is used directly.  If the socketFactory property
     * is not set, the socketFactory.class property is considered.
     * (Note that the SocketFactory property must be set using the
     * <code>put</code> method, not the <code>setProperty</code>
     * method.) <p>
     *
     * If the socketFactory.class property isn't set, the socket
     * returned is an instance of java.net.Socket connected to the
     * given host and port. If the socketFactory.class property is set,
     * it is expected to contain a fully qualified classname of a
     * javax.net.SocketFactory subclass.  In this case, the class is
     * dynamically instantiated and a socket created by that
     * SocketFactory is returned. <p>
     *
     * If the socketFactory.fallback property is set to false, don't
     * fall back to using regular sockets if the socket factory fails. <p>
     *
     * The socketFactory.port specifies a port to use when connecting
     * through the socket factory.  If unset, the port argument will be
     * used.  <p>
     *
     * If the connectiontimeout property is set, the timeout is passed
     * to the socket connect method. <p>
     *
     * If the timeout property is set, it is used to set the socket timeout.
     * <p>
     *
     * If the localaddress property is set, it's used as the local address
     * to bind to.  If the localport property is also set, it's used as the
     * local port number to bind to.
     *
     * @param host The host to connect to
     * @param port The port to connect to at the host
     * @param props Properties object containing socket properties
     * @param prefix Property name prefix, e.g., "mail.imap"
     * @param useSSL use the SSL socket factory as the default
     */
    public static Socket getSocket(String host, int port, Properties props,
				String prefix, boolean useSSL)
				throws IOException {

	if (logger.isLoggable(Level.FINER))
	    logger.finer("getSocket" + ", host " + host + ", port " + port +
				", prefix " + prefix + ", useSSL " + useSSL);
	if (prefix == null)
	    prefix = "socket";
	if (props == null)
	    props = new Properties();	// empty
	int cto = PropUtil.getIntProperty(props,
					prefix + ".connectiontimeout", -1);
	Socket socket = null;
	String localaddrstr = props.getProperty(prefix + ".localaddress", null);
	InetAddress localaddr = null;
	if (localaddrstr != null)
	    localaddr = InetAddress.getByName(localaddrstr);
	int localport = PropUtil.getIntProperty(props,
					prefix + ".localport", 0);

	boolean fb = PropUtil.getBooleanProperty(props,
				prefix + ".socketFactory.fallback", true);

	int sfPort = -1;
	String sfErr = "unknown socket factory";
	int to = PropUtil.getIntProperty(props, prefix + ".timeout", -1);
	try {
	    /*
	     * If using SSL, first look for SSL-specific class name or
	     * factory instance.
	     */
	    SocketFactory sf = null;
	    String sfPortName = null;
	    if (useSSL) {
		Object sfo = props.get(prefix + ".ssl.socketFactory");
		if (sfo instanceof SocketFactory) {
		    sf = (SocketFactory)sfo;
		    sfErr = "SSL socket factory instance " + sf;
		}
		if (sf == null) {
		    String sfClass =
			props.getProperty(prefix + ".ssl.socketFactory.class");
		    sf = getSocketFactory(sfClass);
		    sfErr = "SSL socket factory class " + sfClass;
		}
		sfPortName = ".ssl.socketFactory.port";
	    }

	    if (sf == null) {
		Object sfo = props.get(prefix + ".socketFactory");
		if (sfo instanceof SocketFactory) {
		    sf = (SocketFactory)sfo;
		    sfErr = "socket factory instance " + sf;
		}
		if (sf == null) {
		    String sfClass =
			props.getProperty(prefix + ".socketFactory.class");
		    sf = getSocketFactory(sfClass);
		    sfErr = "socket factory class " + sfClass;
		}
		sfPortName = ".socketFactory.port";
	    }

	    // if we now have a socket factory, use it
	    if (sf != null) {
		sfPort = PropUtil.getIntProperty(props,
						prefix + sfPortName, -1);

		// if port passed in via property isn't valid, use param
		if (sfPort == -1)
		    sfPort = port;
		socket = createSocket(localaddr, localport,
		    host, sfPort, cto, to, props, prefix, sf, useSSL);
	    }
	} catch (SocketTimeoutException sex) {
	    throw sex;
	} catch (Exception ex) {
	    if (!fb) {
		if (ex instanceof InvocationTargetException) {
		    Throwable t =
		      ((InvocationTargetException)ex).getTargetException();
		    if (t instanceof Exception)
			ex = (Exception)t;
		}
		if (ex instanceof IOException)
		    throw (IOException)ex;
		IOException ioex = new IOException(
				    "Couldn't connect using " + sfErr +
				    " to host, port: " +
				    host + ", " + sfPort +
				    "; Exception: " + ex);
		ioex.initCause(ex);
		throw ioex;
	    }
	}

	if (socket == null) {
	    socket = createSocket(localaddr, localport,
		    host, port, cto, to, props, prefix, null, useSSL);

	} else {
	    if (to >= 0)
		socket.setSoTimeout(to);
	}

	return socket;
    }

    public static Socket getSocket(String host, int port, Properties props,
				String prefix) throws IOException {
	return getSocket(host, port, props, prefix, false);
    }

    /**
     * Create a socket with the given local address and connected to
     * the given host and port.  Use the specified connection timeout
     * and read timeout.
     * If a socket factory is specified, use it.  Otherwise, use the
     * SSLSocketFactory if useSSL is true.
     */
    private static Socket createSocket(InetAddress localaddr, int localport,
				String host, int port, int cto, int to,
				Properties props, String prefix,
				SocketFactory sf, boolean useSSL)
				throws IOException {
	Socket socket = null;

	String socksHost = props.getProperty(prefix + ".socks.host", null);
	int socksPort = 1080;
	if (socksHost != null) {
	    int i = socksHost.indexOf(':');
	    if (i >= 0) {
		socksHost = socksHost.substring(0, i);
		try {
		    socksPort = Integer.parseInt(socksHost.substring(i + 1));
		} catch (NumberFormatException ex) {
		    // ignore it
		}
	    }
	    socksPort = PropUtil.getIntProperty(props,
					prefix + ".socks.port", socksPort);
	    if (logger.isLoggable(Level.FINER))
		logger.finer("socks host " + socksHost + ", port " + socksPort);
	}

	if (sf != null)
	    socket = sf.createSocket();
	if (socket == null) {
	    if (socksHost != null)
		socket = new Socket(
				new java.net.Proxy(java.net.Proxy.Type.SOCKS,
				new InetSocketAddress(socksHost, socksPort)));
	    else
		socket = new Socket();
	}
	if (to >= 0)
	    socket.setSoTimeout(to);
	if (localaddr != null)
	    socket.bind(new InetSocketAddress(localaddr, localport));
	if (cto >= 0)
	    socket.connect(new InetSocketAddress(host, port), cto);
	else
	    socket.connect(new InetSocketAddress(host, port));

	/*
	 * If we want an SSL connection and we didn't get an SSLSocket,
	 * wrap our plain Socket with an SSLSocket.
	 */
	if (useSSL && !(socket instanceof SSLSocket)) {
	    String trusted;
	    SSLSocketFactory ssf;
	    if ((trusted = props.getProperty(prefix + ".ssl.trust")) != null) {
		try {
		    MailSSLSocketFactory msf = new MailSSLSocketFactory();
		    if (trusted.equals("*"))
			msf.setTrustAllHosts(true);
		    else
			msf.setTrustedHosts(trusted.split("\\s+"));
		    ssf = msf;
		} catch (GeneralSecurityException gex) {
		    IOException ioex = new IOException(
				    "Can't create MailSSLSocketFactory");
		    ioex.initCause(gex);
		    throw ioex;
		}
	    } else
		ssf = (SSLSocketFactory)SSLSocketFactory.getDefault();
	    socket = ssf.createSocket(socket, host, port, true);
	    sf = ssf;
	}

	/*
	 * No matter how we created the socket, if it turns out to be an
	 * SSLSocket, configure it.
	 */
	configureSSLSocket(socket, host, props, prefix, sf);

	return socket;
    }

    /**
     * Return a socket factory of the specified class.
     */
    private static SocketFactory getSocketFactory(String sfClass)
				throws ClassNotFoundException,
				    NoSuchMethodException,
				    IllegalAccessException,
				    InvocationTargetException {
	if (sfClass == null || sfClass.length() == 0)
	    return null;

	// dynamically load the class 

	ClassLoader cl = getContextClassLoader();
	Class clsSockFact = null;
	if (cl != null) {
	    try {
		clsSockFact = Class.forName(sfClass, false, cl);
	    } catch (ClassNotFoundException cex) { }
	}
	if (clsSockFact == null)
	    clsSockFact = Class.forName(sfClass);
	// get & invoke the getDefault() method
	Method mthGetDefault = clsSockFact.getMethod("getDefault", 
						     new Class[]{});
	SocketFactory sf = (SocketFactory)
	    mthGetDefault.invoke(new Object(), new Object[]{});
	return sf;
    }

    /**
     * Start TLS on an existing socket.
     * Supports the "STARTTLS" command in many protocols.
     * This version for compatibility with possible third party code
     * that might've used this API even though it shouldn't.
     */
    public static Socket startTLS(Socket socket) throws IOException {
	return startTLS(socket, new Properties(), "socket");
    }

    /**
     * Start TLS on an existing socket.
     * Supports the "STARTTLS" command in many protocols.
     * This version for compatibility with possible third party code
     * that might've used this API even though it shouldn't.
     */
    public static Socket startTLS(Socket socket, Properties props,
				String prefix) throws IOException {
	InetAddress a = socket.getInetAddress();
	String host = a.getHostName();
	return startTLS(socket, host, props, prefix);
    }

    /**
     * Start TLS on an existing socket.
     * Supports the "STARTTLS" command in many protocols.
     */
    public static Socket startTLS(Socket socket, String host, Properties props,
				String prefix) throws IOException {
	int port = socket.getPort();
	if (logger.isLoggable(Level.FINER))
	    logger.finer("startTLS host " + host + ", port " + port);

	String sfErr = "unknown socket factory";
	try {
	    SSLSocketFactory ssf = null;
	    SocketFactory sf = null;

	    // first, look for an SSL socket factory
	    Object sfo = props.get(prefix + ".ssl.socketFactory");
	    if (sfo instanceof SocketFactory) {
		sf = (SocketFactory)sfo;
		sfErr = "SSL socket factory instance " + sf;
	    }
	    if (sf == null) {
		String sfClass =
		    props.getProperty(prefix + ".ssl.socketFactory.class");
		sf = getSocketFactory(sfClass);
		sfErr = "SSL socket factory class " + sfClass;
	    }
	    if (sf != null && sf instanceof SSLSocketFactory)
		ssf = (SSLSocketFactory)sf;

	    // next, look for a regular socket factory that happens to be
	    // an SSL socket factory
	    if (ssf == null) {
		sfo = props.get(prefix + ".socketFactory");
		if (sfo instanceof SocketFactory) {
		    sf = (SocketFactory)sfo;
		    sfErr = "socket factory instance " + sf;
		}
		if (sf == null) {
		    String sfClass =
			props.getProperty(prefix + ".socketFactory.class");
		    sf = getSocketFactory(sfClass);
		    sfErr = "socket factory class " + sfClass;
		}
		if (sf != null && sf instanceof SSLSocketFactory)
		    ssf = (SSLSocketFactory)sf;
	    }

	    // finally, use the default SSL socket factory
	    if (ssf == null) {
		String trusted;
		if ((trusted = props.getProperty(prefix + ".ssl.trust")) !=
			null) {
		    try {
			MailSSLSocketFactory msf = new MailSSLSocketFactory();
			if (trusted.equals("*"))
			    msf.setTrustAllHosts(true);
			else
			    msf.setTrustedHosts(trusted.split("\\s+"));
			ssf = msf;
			sfErr = "mail SSL socket factory";
		    } catch (GeneralSecurityException gex) {
			IOException ioex = new IOException(
					"Can't create MailSSLSocketFactory");
			ioex.initCause(gex);
			throw ioex;
		    }
		} else {
		    ssf = (SSLSocketFactory)SSLSocketFactory.getDefault();
		    sfErr = "default SSL socket factory";
		}
	    }

	    socket = ssf.createSocket(socket, host, port, true);
	    configureSSLSocket(socket, host, props, prefix, ssf);
	} catch (Exception ex) {
	    if (ex instanceof InvocationTargetException) {
		Throwable t =
		  ((InvocationTargetException)ex).getTargetException();
		if (t instanceof Exception)
		    ex = (Exception)t;
	    }
	    if (ex instanceof IOException)
		throw (IOException)ex;
	    // wrap anything else before sending it on
	    IOException ioex = new IOException(
				"Exception in startTLS using " + sfErr +
				": host, port: " +
				host + ", " + port +
				"; Exception: " + ex);
	    ioex.initCause(ex);
	    throw ioex;
	}
	return socket;
    }

    /**
     * Configure the SSL options for the socket (if it's an SSL socket),
     * based on the mail.<protocol>.ssl.protocols and
     * mail.<protocol>.ssl.ciphersuites properties.
     * Check the identity of the server as specified by the
     * mail.<protocol>.ssl.checkserveridentity property.
     */
    private static void configureSSLSocket(Socket socket, String host,
			Properties props, String prefix, SocketFactory sf)
			throws IOException {
	if (!(socket instanceof SSLSocket))
	    return;
	SSLSocket sslsocket = (SSLSocket)socket;

	String protocols = props.getProperty(prefix + ".ssl.protocols", null);
	if (protocols != null)
	    sslsocket.setEnabledProtocols(stringArray(protocols));
	else {
	    /*
	     * At least the UW IMAP server insists on only the TLSv1
	     * protocol for STARTTLS, and won't accept the old SSLv2
	     * or SSLv3 protocols.  Here we enable only the TLSv1
	     * protocol.  XXX - this should probably be parameterized.
	     */
	    sslsocket.setEnabledProtocols(new String[] {"TLSv1"});
	}
	String ciphers = props.getProperty(prefix + ".ssl.ciphersuites", null);
	if (ciphers != null)
	    sslsocket.setEnabledCipherSuites(stringArray(ciphers));
	if (logger.isLoggable(Level.FINER)) {
	    logger.finer("SSL protocols after " +
		Arrays.asList(sslsocket.getEnabledProtocols()));
	    logger.finer("SSL ciphers after " +
		Arrays.asList(sslsocket.getEnabledCipherSuites()));
	}

	/*
	 * Force the handshake to be done now so that we can report any
	 * errors (e.g., certificate errors) to the caller of the startTLS
	 * method.
	 */
	sslsocket.startHandshake();

	/*
	 * Check server identity and trust.
	 */
	boolean idCheck = PropUtil.getBooleanProperty(props,
			    prefix + ".ssl.checkserveridentity", false);
	if (idCheck)
	    checkServerIdentity(host, sslsocket);
	if (sf instanceof MailSSLSocketFactory) {
	    MailSSLSocketFactory msf = (MailSSLSocketFactory)sf;
	    if (!msf.isServerTrusted(host, sslsocket)) {
		try {
		    sslsocket.close();
		} finally {
		    throw new IOException("Server is not trusted: " + host);
		}
	    }
	}
    }

    /**
     * Check the server from the Socket connection against the server name(s)
     * as expressed in the server certificate (RFC 2595 check).
     * 
     * @param	server		name of the server expected
     * @param   sslSocket	SSLSocket connected to the server
     * @return  true if the RFC 2595 check passes
     */
    private static void checkServerIdentity(String server, SSLSocket sslSocket)
				throws IOException {

	// Check against the server name(s) as expressed in server certificate
	try {
	    java.security.cert.Certificate[] certChain =
		      sslSocket.getSession().getPeerCertificates();
	    if (certChain != null && certChain.length > 0 &&
		    certChain[0] instanceof X509Certificate &&
		    matchCert(server, (X509Certificate)certChain[0]))
		return;
	} catch (SSLPeerUnverifiedException e) {
	    sslSocket.close();
	    IOException ioex = new IOException(
		"Can't verify identity of server: " + server);
	    ioex.initCause(e);
	    throw ioex;
	}

	// If we get here, there is nothing to consider the server as trusted.
	sslSocket.close();
	throw new IOException("Can't verify identity of server: " + server);
    }

    /**
     * Do any of the names in the cert match the server name?
     *  
     * @param	server	name of the server expected
     * @param   cert	X509Certificate to get the subject's name from
     * @return  true if it matches
     */
    private static boolean matchCert(String server, X509Certificate cert) {
	if (logger.isLoggable(Level.FINER))
	    logger.finer("matchCert server " +
		server + ", cert " + cert);

	/*
	 * First, try to use sun.security.util.HostnameChecker,
	 * which exists in Sun's JDK starting with 1.4.1.
	 * We use reflection to access it in case it's not available
	 * in the JDK we're running on.
	 */
	try {
	    Class hnc = Class.forName("sun.security.util.HostnameChecker");
	    // invoke HostnameChecker.getInstance(HostnameChecker.TYPE_LDAP)
	    // HostnameChecker.TYPE_LDAP == 2
	    // LDAP requires the same regex handling as we need
	    Method getInstance = hnc.getMethod("getInstance", 
					new Class[] { byte.class });
	    Object hostnameChecker = getInstance.invoke(new Object(),
					new Object[] { new Byte((byte)2) });

	    // invoke hostnameChecker.match( server, cert)
	    if (logger.isLoggable(Level.FINER))
		logger.finer("using sun.security.util.HostnameChecker");
	    Method match = hnc.getMethod("match",
			new Class[] { String.class, X509Certificate.class });
	    try {
		match.invoke(hostnameChecker, new Object[] { server, cert });
		return true;
	    } catch (InvocationTargetException cex) {
		logger.log(Level.FINER, "FAIL", cex);
		return false;
	    }
	} catch (Exception ex) {
	    logger.log(Level.FINER, "NO sun.security.util.HostnameChecker", ex);
	    // ignore failure and continue below
	}

	/*
	 * Lacking HostnameChecker, we implement a crude version of
	 * the same checks ourselves.
	 */
	try {
	    /*
	     * Check each of the subjectAltNames.
	     * XXX - only checks DNS names, should also handle
	     * case where server name is a literal IP address
	     */
	    Collection names = cert.getSubjectAlternativeNames();
	    if (names != null) {
		boolean foundName = false;
		for (Iterator it = names.iterator(); it.hasNext(); ) {
		    List nameEnt = (List)it.next();
		    Integer type = (Integer)nameEnt.get(0);
		    if (type.intValue() == 2) {	// 2 == dNSName
			foundName = true;
			String name = (String)nameEnt.get(1);
			if (logger.isLoggable(Level.FINER))
			    logger.finer("found name: " + name);
			if (matchServer(server, name))
			    return true;
		    }
		}
		if (foundName)	// found a name, but no match
		    return false;
	    }
	} catch (CertificateParsingException ex) {
	    // ignore it
	}

	// XXX - following is a *very* crude parse of the name and ignores
	//	 all sorts of important issues such as quoting
	Pattern p = Pattern.compile("CN=([^,]*)");
	Matcher m = p.matcher(cert.getSubjectX500Principal().getName());
	if (m.find() && matchServer(server, m.group(1).trim()))
	    return true;

	return false;
    }

    /**
     * Does the server we're expecting to connect to match the
     * given name from the server's certificate?
     *
     * @param	server		name of the server expected
     * @param	name		name from the server's certificate
     */
    private static boolean matchServer(String server, String name) {
	if (logger.isLoggable(Level.FINER))
	    logger.finer("match server " + server + " with " + name);
	if (name.startsWith("*.")) {
	    // match "foo.example.com" with "*.example.com"
	    String tail = name.substring(2);
	    if (tail.length() == 0)
		return false;
	    int off = server.length() - tail.length();
	    if (off < 1)
		return false;
	    // if tail matches and is preceeded by "."
	    return server.charAt(off - 1) == '.' &&
		    server.regionMatches(true, off, tail, 0, tail.length());
	} else
	    return server.equalsIgnoreCase(name);
    }

    /**
     * Parse a string into whitespace separated tokens
     * and return the tokens in an array.
     */
    private static String[] stringArray(String s) {
	StringTokenizer st = new StringTokenizer(s);
	List tokens = new ArrayList();
	while (st.hasMoreTokens())
	    tokens.add(st.nextToken());
	return (String[])tokens.toArray(new String[tokens.size()]);
    }

    /**
     * Convenience method to get our context class loader.
     * Assert any privileges we might have and then call the
     * Thread.getContextClassLoader method.
     */
    private static ClassLoader getContextClassLoader() {
	return (ClassLoader)
		AccessController.doPrivileged(new PrivilegedAction() {
	    public Object run() {
		ClassLoader cl = null;
		try {
		    cl = Thread.currentThread().getContextClassLoader();
		} catch (SecurityException ex) { }
		return cl;
	    }
	});
    }
}
