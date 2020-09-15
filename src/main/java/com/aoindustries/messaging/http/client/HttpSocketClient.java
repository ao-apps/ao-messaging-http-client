/*
 * ao-messaging-http-client - Client for asynchronous bidirectional messaging over HTTP.
 * Copyright (C) 2014, 2015, 2016, 2019, 2020  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of ao-messaging-http-client.
 *
 * ao-messaging-http-client is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ao-messaging-http-client is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with ao-messaging-http-client.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.messaging.http.client;

import com.aoindustries.concurrent.Callback;
import com.aoindustries.concurrent.Executors;
import com.aoindustries.io.AoByteArrayOutputStream;
import com.aoindustries.lang.Throwables;
import com.aoindustries.messaging.http.HttpSocket;
import com.aoindustries.messaging.http.HttpSocketContext;
import com.aoindustries.security.Identifier;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilder;
import org.w3c.dom.Element;

/**
 * Client component for bi-directional messaging over HTTP.
 */
public class HttpSocketClient extends HttpSocketContext {

	private static final Logger logger = Logger.getLogger(HttpSocketClient.class.getName());

	private static final int CONNECT_TIMEOUT = 15 * 1000;

	private final Executors executors = new Executors();

	public HttpSocketClient() {
	}

	@Override
	public void close() {
		try {
			super.close();
		} finally {
			executors.dispose();
		}
	}

	/**
	 * Asynchronously connects.
	 */
	@SuppressWarnings({"UseSpecificCatch", "TooBroadCatch", "AssignmentToCatchBlockParameter"})
	public void connect(
		String endpoint,
		Callback<? super HttpSocket> onConnect,
		Callback<? super Throwable> onError
	) {
		executors.getUnbounded().submit(() -> {
			try {
				// Build request bytes
				AoByteArrayOutputStream bout = new AoByteArrayOutputStream();
				try {
					try (DataOutputStream out = new DataOutputStream(bout)) {
						out.writeBytes("action=connect");
					}
				} finally {
					bout.close();
				}
				long connectTime = System.currentTimeMillis();
				URL endpointURL = new URL(endpoint);
				HttpURLConnection conn = (HttpURLConnection)endpointURL.openConnection();
				conn.setAllowUserInteraction(false);
				conn.setConnectTimeout(CONNECT_TIMEOUT);
				conn.setDoOutput(true);
				conn.setFixedLengthStreamingMode(bout.size());
				conn.setInstanceFollowRedirects(false);
				conn.setReadTimeout(CONNECT_TIMEOUT);
				conn.setRequestMethod("POST");
				conn.setUseCaches(false);
				// Write request
				OutputStream out = conn.getOutputStream();
				try {
					out.write(bout.getInternalByteArray(), 0, bout.size());
					out.flush();
				} finally {
					out.close();
				}
				// Get response
				int responseCode = conn.getResponseCode();
				logger.log(Level.FINEST, "Got connection with response: {0}", responseCode);
				if(responseCode != 200) throw new IOException("Unexpect response code: " + responseCode);
				DocumentBuilder builder = builderFactory.newDocumentBuilder();
				Element document = builder.parse(conn.getInputStream()).getDocumentElement();
				if(!"connection".equals(document.getNodeName())) throw new IOException("Unexpected root node name: " + document.getNodeName());
				Identifier id = Identifier.valueOf(document.getAttribute("id"));
				logger.log(Level.FINEST, "Got id = ", id);
				HttpSocket httpSocket = new HttpSocket(
					HttpSocketClient.this,
					id,
					connectTime,
					endpointURL
				);
				logger.log(Level.FINEST, "Adding socket");
				addSocket(httpSocket);
				if(onConnect != null) {
					logger.log(Level.FINE, "Calling onConnect: {0}", httpSocket);
					try {
						onConnect.call(httpSocket);
					} catch(ThreadDeath td) {
						throw td;
					} catch(Throwable t) {
						logger.log(Level.SEVERE, null, t);
					}
				} else {
					logger.log(Level.FINE, "No onConnect: {0}", httpSocket);
				}
			} catch(Throwable t0) {
				if(onError != null) {
					logger.log(Level.FINE, "Calling onError", t0);
					try {
						onError.call(t0);
					} catch(ThreadDeath td) {
						t0 = Throwables.addSuppressed(td, t0);
						assert t0 == td;
					} catch(Throwable t2) {
						logger.log(Level.SEVERE, null, t2);
					}
				} else {
					logger.log(Level.FINE, "No onError", t0);
				}
				if(t0 instanceof ThreadDeath) throw (ThreadDeath)t0;
			}
		});
	}
}
