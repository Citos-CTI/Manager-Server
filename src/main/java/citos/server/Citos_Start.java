/*
 * Copyright (c) 2018.  Johannes Engler, Citos CTI
 */
package citos.server;

import citos.server.database.SqlLiteCallRecordDatabase;
import citos.server.database.SqliteUserDatabase;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.apache.log4j.BasicConfigurator;

import javax.net.ssl.SSLException;
import java.io.File;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.HashMap;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Citos_Start
{
    private int port =12345;
    public static void main(String[] args)
    {
        new Citos_Start();
    }
    public Citos_Start()
    {
        BasicConfigurator.configure();
        try {

            HashMap<String, String> config = (HashMap<String, String>) ConfigLoader.loadConfig();
            if (config.containsKey("working_directory")) {
                System.setProperty("user.dir", config.get("working_directory"));
                SqliteUserDatabase.initInstance(config.get("working_directory"));
                SqlLiteCallRecordDatabase.setWorkingDir(config.get("working_directory"));
            } else {
                String default_data_directory = getDefaultDataDirectory();
                SqliteUserDatabase.initInstance(default_data_directory);
                SqlLiteCallRecordDatabase.setWorkingDir(default_data_directory);
            }
            if(config.containsKey("own_server_port")) {
                this.port = Integer.parseInt(config.get("own_server_port"));
            }

            if (!config.containsKey("plugin")) {
                System.out.println("No PBX plugin in server.conf defined. Exiting....");
                System.exit(0);
            }
            //TODO add function in upcoming frontend
            if(config.containsKey("import_folder")) {
                int imported = CSVImporter.importCSVtoUserDatabase(config.get("import_folder"), "users.csv");
                Logger.getLogger(getClass().getName()).info("File imported from: " + config.get("import_folder") + " imported " + imported + " users");
            }

            PluginInterface amiInt = null;

            switch (config.get("plugin")) {
                case "asterisk":
                    if (!(config.containsKey("port") &&
                            config.containsKey("password") &&
                            config.containsKey("username") &&
                            config.containsKey("server_address"))) {
                        System.out.println("Failure in config for plugin: " + config.get("plugin") + ". Exiting...");
                        System.exit(0);
                    }
                    amiInt = new PluginAsteriskJava(config.get("server_address"),
                            config.get("username"),
                            config.get("password"),
                            Integer.parseInt(config.get("port")));
                    break;
                case "dummy":
                    amiInt = new PluginDummy();
                    break;
                case "dummyheadless":
                    amiInt = new PluginDummyHeadless();
                    break;
                default:
                    System.out.println("The plugin you specified is not installed. Please look for typos or install the plugin. For further information see the documentation. Exiting...");
                    System.exit(0);
            }

            ChannelController channelController = new ChannelController(amiInt);

            // To generate some random CDR packets only relevant in docker demo
            Logger.getLogger(getClass().getName()).info("Generating some random cdr data for extension 200.");
            PluginDummyHeadless pl = (PluginDummyHeadless) amiInt;
            pl.generateRandomCdrPacket(100, "200");


            // Netty part for communication with clients
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            SslContext sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
                    .build();
            EventLoopGroup bossGroup = new NioEventLoopGroup(1);
            EventLoopGroup workerGroup = new NioEventLoopGroup();
            try {
                ServerBootstrap b = new ServerBootstrap();
                b.group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .handler(new LoggingHandler(LogLevel.INFO))
                        .childHandler(new SecureChatServerInitializer(sslCtx, channelController));
                
                b.bind(port).sync().channel().closeFuture().sync();
            } catch (InterruptedException ex) {
                Logger.getLogger(Citos_Start.class.getName()).log(Level.SEVERE, null, ex);
            } finally {
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
            }
            Logger.getLogger(getClass().getName()).info("STARTED");
        } catch (CertificateException | SSLException ex) {
            Logger.getLogger(Citos_Start.class.getName()).log(Level.SEVERE, null, ex);
        }
          
    }

    public static String getDefaultDataDirectory() {
        String path = System.getProperty("user.home") + File.separator + ".citos-server" + File.separator + getApplicationVersionString() + File.separator;
        new File(path).mkdirs();
        return path;
    }

    public static String getApplicationVersionString() {
        final Properties properties = new Properties();
        try {
            properties.load(Citos_Start.class.getClassLoader().getResourceAsStream("project.properties"));
        } catch (IOException e) {
            e.printStackTrace();
        }
        return properties.getProperty("version");
    }
}