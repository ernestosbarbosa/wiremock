/*
 * Copyright (C) 2011 Thomas Akehurst
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.tomakehurst.wiremock.jetty9;

import static com.github.tomakehurst.wiremock.common.Exceptions.throwUnchecked;
import static com.github.tomakehurst.wiremock.core.WireMockApp.ADMIN_CONTEXT_ROOT;

import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.EnumSet;

import javax.servlet.DispatcherType;

import com.github.tomakehurst.wiremock.common.*;
import com.github.tomakehurst.wiremock.core.WireMockApp;
import com.github.tomakehurst.wiremock.http.trafficlistener.WiremockNetworkTrafficListener;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.NetworkTrafficListener;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.eclipse.jetty.servlets.GzipFilter;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import com.github.tomakehurst.wiremock.core.Options;
import com.github.tomakehurst.wiremock.http.AdminRequestHandler;
import com.github.tomakehurst.wiremock.http.HttpServer;
import com.github.tomakehurst.wiremock.http.RequestHandler;
import com.github.tomakehurst.wiremock.http.StubRequestHandler;
import com.github.tomakehurst.wiremock.servlet.ContentTypeSettingFilter;
import com.github.tomakehurst.wiremock.servlet.FaultInjectorFactory;
import com.github.tomakehurst.wiremock.servlet.TrailingSlashFilter;
import com.github.tomakehurst.wiremock.servlet.WireMockHandlerDispatchingServlet;

class JettyHttpServer implements HttpServer {

    private static final String FILES_URL_MATCH = String.format("/%s/*", WireMockApp.FILES_ROOT);

    private final Server jettyServer;
    private final ServerConnector httpConnector;
    private final ServerConnector httpsConnector;

    JettyHttpServer(
            Options options,
            AdminRequestHandler adminRequestHandler,
            StubRequestHandler stubRequestHandler
    ) {
        QueuedThreadPool threadPool = new QueuedThreadPool(options.containerThreads());
        jettyServer = new Server(threadPool);

        NetworkTrafficListenerAdapter networkTrafficListenerAdapter = new NetworkTrafficListenerAdapter(options.networkTrafficListener());
        httpConnector = createHttpConnector(
                options.bindAddress(),
                options.portNumber(),
                options.jettySettings(),
                networkTrafficListenerAdapter
        );
        jettyServer.addConnector(httpConnector);

        if (options.httpsSettings().enabled()) {
            httpsConnector = createHttpsConnector(
                    options.httpsSettings(),
                    options.jettySettings(),
                    networkTrafficListenerAdapter);
            jettyServer.addConnector(httpsConnector);
        } else {
            httpsConnector = null;
        }

        Notifier notifier = options.notifier();
        ServletContextHandler adminContext = addAdminContext(
                adminRequestHandler,
                notifier
        );
        ServletContextHandler mockServiceContext = addMockServiceContext(
                stubRequestHandler,
                options.filesRoot(),
                notifier
        );

        HandlerCollection handlers = new HandlerCollection();
        handlers.setHandlers(new Handler[]{adminContext, mockServiceContext});
        jettyServer.setHandler(handlers);

        jettyServer.setStopTimeout(0);
    }

    @Override
    public void start() {
        try {
            jettyServer.start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        long timeout = System.currentTimeMillis() + 30000;
        while (!jettyServer.isStarted()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // no-op
            }
            if (System.currentTimeMillis() > timeout) {
                throw new RuntimeException("Server took too long to start up.");
            }
        }
    }

    @Override
    public void stop() {
        try {
            jettyServer.stop();
            jettyServer.join();
        } catch (Exception e) {
            throwUnchecked(e);
        }
    }

    @Override
    public boolean isRunning() {
        return jettyServer.isRunning();
    }

    @Override
    public int port() {
        return httpConnector.getLocalPort();
    }

    @Override
    public int httpsPort() {
        return httpsConnector.getLocalPort();
    }

    private ServerConnector createHttpConnector(
            String bindAddress,
            int port,
            JettySettings jettySettings,
            NetworkTrafficListener listener) {

        HttpConfiguration httpConfig = createHttpConfig(jettySettings);

        ServerConnector connector = createServerConnector(
                jettySettings,
                port,
                listener,
                new HttpConnectionFactory(httpConfig)
        );
        connector.setHost(bindAddress);
        return connector;
    }

    private ServerConnector createHttpsConnector(
            HttpsSettings httpsSettings,
            JettySettings jettySettings,
            NetworkTrafficListener listener) {

        //Added to support Android https communication.
        CustomizedSslContextFactory sslContextFactory = new CustomizedSslContextFactory();

        sslContextFactory.setKeyStorePath(httpsSettings.keyStorePath());
        sslContextFactory.setKeyManagerPassword(httpsSettings.keyStorePassword());
        sslContextFactory.setKeyStoreType(httpsSettings.keyStoreType());
        if (httpsSettings.hasTrustStore()) {
            sslContextFactory.setTrustStorePath(httpsSettings.trustStorePath());
            sslContextFactory.setTrustStorePassword(httpsSettings.trustStorePassword());
            sslContextFactory.setTrustStoreType(httpsSettings.trustStoreType());
        }
        sslContextFactory.setNeedClientAuth(httpsSettings.needClientAuth());

        HttpConfiguration httpConfig = createHttpConfig(jettySettings);
        httpConfig.addCustomizer(new SecureRequestCustomizer());

        final int port = httpsSettings.port();


        return createServerConnector(
                jettySettings,
                port,
                listener,
                new SslConnectionFactory(
                        sslContextFactory,
                        "http/1.1"
                ),
                new HttpConnectionFactory(httpConfig)
        );
    }

    private HttpConfiguration createHttpConfig(JettySettings jettySettings) {
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setRequestHeaderSize(
                jettySettings.getRequestHeaderSize().or(8192)
        );
        httpConfig.setSendDateHeader(false);
        return httpConfig;
    }

    private ServerConnector createServerConnector(JettySettings jettySettings, int port, NetworkTrafficListener listener, ConnectionFactory... connectionFactories) {
        int acceptors = jettySettings.getAcceptors().or(2);
        NetworkTrafficServerConnector connector = new NetworkTrafficServerConnector(
                jettyServer,
                null,
                null,
                null,
                acceptors,
                2,
                connectionFactories
        );
        connector.setPort(port);

        connector.setStopTimeout(0);
        connector.getSelectorManager().setStopTimeout(0);

        connector.addNetworkTrafficListener(listener);

        setJettySettings(jettySettings, connector);

        return connector;
    }

    private void setJettySettings(JettySettings jettySettings, ServerConnector connector) {
        if (jettySettings.getAcceptQueueSize().isPresent()) {
            connector.setAcceptQueueSize(jettySettings.getAcceptQueueSize().get());
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked" })
    private ServletContextHandler addMockServiceContext(
            StubRequestHandler stubRequestHandler,
            FileSource fileSource,
            Notifier notifier
    ) {
        ServletContextHandler mockServiceContext = new ServletContextHandler(jettyServer, "/");

        mockServiceContext.setInitParameter("org.eclipse.jetty.servlet.Default.maxCacheSize", "0");
        mockServiceContext.setInitParameter("org.eclipse.jetty.servlet.Default.resourceBase", fileSource.getPath());
        mockServiceContext.setInitParameter("org.eclipse.jetty.servlet.Default.dirAllowed", "false");

        mockServiceContext.addServlet(DefaultServlet.class, FILES_URL_MATCH);

        mockServiceContext.setAttribute(JettyFaultInjectorFactory.class.getName(), new JettyFaultInjectorFactory());
        mockServiceContext.setAttribute(StubRequestHandler.class.getName(), stubRequestHandler);
        mockServiceContext.setAttribute(Notifier.KEY, notifier);
        ServletHolder servletHolder = mockServiceContext.addServlet(WireMockHandlerDispatchingServlet.class, "/");
        servletHolder.setInitParameter(RequestHandler.HANDLER_CLASS_KEY, StubRequestHandler.class.getName());
        servletHolder.setInitParameter(FaultInjectorFactory.INJECTOR_CLASS_KEY, JettyFaultInjectorFactory.class.getName());
        servletHolder.setInitParameter(WireMockHandlerDispatchingServlet.SHOULD_FORWARD_TO_FILES_CONTEXT, "true");

        MimeTypes mimeTypes = new MimeTypes();
        mimeTypes.addMimeMapping("json", "application/json");
        mimeTypes.addMimeMapping("html", "text/html");
        mimeTypes.addMimeMapping("xml", "application/xml");
        mimeTypes.addMimeMapping("txt", "text/plain");
        mockServiceContext.setMimeTypes(mimeTypes);

        mockServiceContext.setWelcomeFiles(new String[]{"index.json", "index.html", "index.xml", "index.txt"});

        mockServiceContext.addFilter(GzipFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD));
        mockServiceContext.addFilter(ContentTypeSettingFilter.class, FILES_URL_MATCH, EnumSet.of(DispatcherType.FORWARD));
        mockServiceContext.addFilter(TrailingSlashFilter.class, FILES_URL_MATCH, EnumSet.allOf(DispatcherType.class));

        return mockServiceContext;
    }

    private ServletContextHandler addAdminContext(
            AdminRequestHandler adminRequestHandler,
            Notifier notifier
    ) {
        ServletContextHandler adminContext = new ServletContextHandler(jettyServer, ADMIN_CONTEXT_ROOT);

        adminContext.setInitParameter("org.eclipse.jetty.servlet.Default.maxCacheSize", "0");
        adminContext.setInitParameter("org.eclipse.jetty.servlet.Default.resourceBase", Resources.getResource("assets").toString());
        adminContext.setInitParameter("org.eclipse.jetty.servlet.Default.dirAllowed", "false");
        adminContext.addServlet(DefaultServlet.class, "/swagger-ui/*");

        ServletHolder servletHolder = adminContext.addServlet(WireMockHandlerDispatchingServlet.class, "/");
        servletHolder.setInitParameter(RequestHandler.HANDLER_CLASS_KEY, AdminRequestHandler.class.getName());
        adminContext.setAttribute(AdminRequestHandler.class.getName(), adminRequestHandler);
        adminContext.setAttribute(Notifier.KEY, notifier);

        FilterHolder filterHolder = new FilterHolder(CrossOriginFilter.class);
        filterHolder.setInitParameters(ImmutableMap.of(
            "chainPreflight", "false",
            "allowedOrigins", "*",
            "allowedHeaders", "X-Requested-With,Content-Type,Accept,Origin,Authorization",
            "allowedMethods", "OPTIONS,GET,POST,PUT,PATCH,DELETE"));

        adminContext.addFilter(filterHolder, "/*", EnumSet.of(DispatcherType.REQUEST));

        return adminContext;
    }

    private static class NetworkTrafficListenerAdapter implements NetworkTrafficListener {
        private final WiremockNetworkTrafficListener wiremockNetworkTrafficListener;

        NetworkTrafficListenerAdapter(WiremockNetworkTrafficListener wiremockNetworkTrafficListener) {
            this.wiremockNetworkTrafficListener = wiremockNetworkTrafficListener;
        }

        @Override
        public void opened(Socket socket) {
            wiremockNetworkTrafficListener.opened(socket);
        }

        @Override
        public void incoming(Socket socket, ByteBuffer bytes) {
            wiremockNetworkTrafficListener.incoming(socket, bytes);
        }

        @Override
        public void outgoing(Socket socket, ByteBuffer bytes) {
            wiremockNetworkTrafficListener.outgoing(socket, bytes);
        }

        @Override
        public void closed(Socket socket) {
            wiremockNetworkTrafficListener.closed(socket);
        }
    }
}
