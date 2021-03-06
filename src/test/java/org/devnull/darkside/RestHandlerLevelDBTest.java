package org.devnull.darkside;

import org.devnull.darkside.backends.BackendDB;
import org.devnull.darkside.configs.DarksideConfig;
import org.devnull.darkside.configs.LevelDBConfig;
import org.glassfish.jersey.server.ServerProperties;
import org.glassfish.jersey.servlet.ServletContainer;
import org.apache.commons.io.FileUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.auth.params.AuthPNames;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.params.AuthPolicy;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.BasicClientConnectionManager;
import org.apache.http.protocol.HttpContext;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.devnull.darkside.records.AAAARecord;
import org.devnull.darkside.records.ARecord;
import org.devnull.darkside.records.Record;
import org.devnull.statsd_client.StatsObject;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.util.*;

import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertTrue;
import static org.testng.AssertJUnit.assertEquals;

public class RestHandlerLevelDBTest extends JsonBase
{
	private static final Logger log = Logger.getLogger(RestHandlerLevelDBTest.class);

	private Server server = null;
	private BackendDB db = null;
	private static String dbPath = "/tmp/leveldbtest";

	private static enum Type
	{
		GET, POST, DELETE
	}

	/**
	 * This sets up a jetty server with the resthandler and tests all of the endpoints
	 * therein.  The code here is mostly identical to the code in Darkside.java.
	 *
	 * @throws Exception
	 */
	@BeforeClass
	public void setUp() throws Exception
	{
		StatsObject.getInstance().clear();

		Properties logProperties = new Properties();

		logProperties.put("log4j.rootLogger", "DEBUG, stdout");
		logProperties.put("log4j.appender.stdout", "org.apache.log4j.ConsoleAppender");
		logProperties.put("log4j.appender.stdout.layout", "org.apache.log4j.EnhancedPatternLayout");
		logProperties.put("log4j.appender.stdout.layout.ConversionPattern", "%d [%F:%L] [%p] %C: %m%n");
		logProperties.put("log4j.appender.stdout.immediateFlush", "true");

		BasicConfigurator.resetConfiguration();
		PropertyConfigurator.configure(logProperties);

		File f = new File(dbPath);
		if (f.exists())
		{
			log.debug("deleting leveldb cache: " + dbPath);
			FileUtils.deleteDirectory(f);
		}

		LevelDBConfig leveldbConfig = new LevelDBConfig();
		leveldbConfig.dbPath = dbPath;
		leveldbConfig.maxItems = 10;

		db = DBFactory.getBackendDBInstance("leveldb", mapper.valueToTree(leveldbConfig));

		StuffHolder.getInstance().setDB(db);
		StuffHolder.getInstance().setConfig(new DarksideConfig());

		server = new Server(new InetSocketAddress("127.0.0.1", 8181));

		ServletHolder servletHolder = new ServletHolder(ServletContainer.class);

        servletHolder.setInitParameter(ServerProperties.PROVIDER_PACKAGES, "org.devnull.darkside");

		ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
		context.setContextPath("/");
		context.addServlet(servletHolder, "/*");

		context.setSecurityHandler(Darkside.getDigestAuthHandler("foo", "bar"));

		server.setHandler(context);

		QueuedThreadPool queuedThreadPool = new QueuedThreadPool(20);
		queuedThreadPool.setName("RestHandlerThread");
		server.setThreadPool(queuedThreadPool);
		server.start();
	}

	/**
	 * A server has been set up and started, and we can make http client requests against it.
	 * I really would have preferred to use Jersey's client library here, but the lack of examples
	 * on how to use it really slowed that down.
	 *
	 * @throws Exception on any exceptions uncaught.
	 */
	@Test
	public void testAll() throws Exception
	{
		// get test 1: create an unauthenticated http digest request against server, expect fail

		log.debug("making unauthenticated request for fqdn foo");

		HttpResponse response = getResponse(Type.GET, false, "foo", null);
		HttpEntity entity = response.getEntity();

		int status = response.getStatusLine().getStatusCode();

		assertTrue("status is: " + status, status == 401);

		if (null != entity)
		{
			entity.getContent().close();
		}

		// get test 2: fetch an fqdn that does not exist in db, expect 404

		log.debug("fetching an fqdn that does not exist in the db");

		response = getResponse(Type.GET, true, "www.google.com", null);
		entity = response.getEntity();
		status = response.getStatusLine().getStatusCode();

		assertTrue("status is: " + status, status == 404);

		if (entity != null)
		{
			entity.getContent().close();
		}

		// delete test 1: try to delete fqdn that does not exist, expect 200, no error

		log.debug("deleting an fqdn that does not exist in the db");

		response = getResponse(Type.DELETE, true, "www.google.com", null);
		entity = response.getEntity();
		status = response.getStatusLine().getStatusCode();

		assertTrue("status is: " + status, status == 200);

		if (entity != null)
		{
			entity.getContent().close();
		}

		// post test 1: try to post with empty body

		log.debug("creating an fqdn that does not exist in the db with a null record");

		response = getResponse(Type.POST, true, "www.google.com", null);
		entity = response.getEntity();
		status = response.getStatusLine().getStatusCode();

		assertTrue("status is: " + status, status == 412);

		if (entity != null)
		{
			entity.getContent().close();
		}

		// post test 2: try to post with incomplete record, expect fail

		log.debug("creating an fqdn that does not exist in the db with incomplete records");

		DNSRecordSet r = new DNSRecordSet();
		response = getResponse(Type.POST, true, "www.google.com", r);
		entity = response.getEntity();
		status = response.getStatusLine().getStatusCode();

        assertEquals(412, status);

		BufferedReader br = new BufferedReader(new InputStreamReader(entity.getContent()));
		String line = br.readLine();

		assertNotNull(line);
		assertTrue(line, line.equals("{\"error\":\"no records were specified\"}"));

		br.close();

		if (entity != null)
		{
			entity.getContent().close();
		}

		log.debug("creating an fqdn that does not exist in the db with incomplete records");

		r = new DNSRecordSet();
		r.setRecords(new ArrayList<Record>());
		response = getResponse(Type.POST, true, "www.google.com", r);
		entity = response.getEntity();
		status = response.getStatusLine().getStatusCode();

        assertEquals(412, status);

		br = new BufferedReader(new InputStreamReader(entity.getContent()));
		line = br.readLine();

		assertNotNull(line);
		assertTrue(line, line.equals("{\"error\":\"records list was empty\"}"));

		if (entity != null)
		{
			entity.getContent().close();
		}

		// post test 3: post with complete record with no ttl

		log.debug("creating an fqdn that does not exist in the db with complete record with no ttl");

		r = new DNSRecordSet();
		List<Record> ips = new ArrayList<Record>();
		ARecord a = new ARecord();
		a.setAddress("1.1.1.1");
		ips.add(a);
		r.setRecords(ips);
		response = getResponse(Type.POST, true, "www.google.com", r);
		entity = response.getEntity();
		status = response.getStatusLine().getStatusCode();

		assertTrue("status is: " + status, status == 200);

		if (entity != null)
		{
			entity.getContent().close();
		}

		// post test 4: post with complete record with specific ttl

		r = new DNSRecordSet();
		ips = new ArrayList<Record>();
		ips.add(a);
		r.setRecords(ips);
		r.setTtl(100);
		response = getResponse(Type.POST, true, "ttl100.google.com", r);
		entity = response.getEntity();
		status = response.getStatusLine().getStatusCode();

		assertTrue("status is: " + status, status == 200);

		if (entity != null)
		{
			entity.getContent().close();
		}

		// get test 3: fetch an fqdn that does exist but does not have ttl set, expect default ttl from config

		response = getResponse(Type.GET, true, "www.google.com", null);
		entity = response.getEntity();
		status = response.getStatusLine().getStatusCode();

		assertTrue("status is: " + status, status == 200);

		br = new BufferedReader(new InputStreamReader(entity.getContent()));
		line = br.readLine();

		assertNotNull(line);
		assertTrue(line,
			   line.equals("{\"fqdn\":\"www.google.com\",\"ttl\":300,\"records\":[{\"address\":\"1.1.1.1\",\"type\":\"A\"}]}") ||
	  		   line.equals("{\"fqdn\":\"www.google.com\",\"ttl\":300,\"records\":[{\"type\":\"A\",\"address\":\"1.1.1.1\"}]}"));

		if (entity != null)
		{
			entity.getContent().close();
		}

		// get test 4: fetch an fqdn that does exist and has ttl set, expect specific ttl

		response = getResponse(Type.GET, true, "ttl100.google.com", null);
		entity = response.getEntity();
		status = response.getStatusLine().getStatusCode();

		assertTrue("status is: " + status, status == 200);

		br = new BufferedReader(new InputStreamReader(entity.getContent()));
		line = br.readLine();

		assertNotNull(line);
		assertTrue(line, line.equals(
			"{\"fqdn\":\"ttl100.google.com\",\"ttl\":100,\"records\":[{\"type\":\"A\",\"address\":\"1.1.1.1\"}]}"));

		if (entity != null)
		{
			entity.getContent().close();
		}

		// delete test 3: delete both records that were created earlier

		response = getResponse(Type.DELETE, true, "www.google.com", null);
		entity = response.getEntity();
		status = response.getStatusLine().getStatusCode();

		assertTrue("status is: " + status, status == 200);

		if (entity != null)
		{
			entity.getContent().close();
		}

		response = getResponse(Type.DELETE, true, "ttl100.google.com", null);
		entity = response.getEntity();
		status = response.getStatusLine().getStatusCode();

		assertTrue("status is: " + status, status == 200);

		if (entity != null)
		{
			entity.getContent().close();
		}

		// stats test: test results of statsobject, make sure counters line up with expectations

		StatsObject so = StatsObject.getInstance();
		TreeMap<String, Long> map = new TreeMap<String, Long>(so.getMap());
        assertNotNull(map);
        assertTrue(!map.isEmpty());

        //
        // additional tests
        //
        log.debug("creating a specific fqdn");

        r = new DNSRecordSet();
        ips = new ArrayList<Record>();
        a = new ARecord();
        a.setAddress("50.203.224.4");
        Record aaaa = new AAAARecord();
        aaaa.setAddress("2001:470:813b::1761:0:902");
        ips.add(a);
        ips.add(aaaa);
        r.setRecords(ips);
        r.setTtl(null);
        response = getResponse(Type.POST, true, "PUTIN.porked.the.peace", r);
        entity = response.getEntity();
        status = response.getStatusLine().getStatusCode();

        assertTrue("status is: " + status, status == 200);

        if (entity != null)
        {
            entity.getContent().close();
        }

        // fetch that fqdn and make sure the records match expectations

        response = getResponse(Type.GET, true, "putin.PORKED.the.peace", null);
        entity = response.getEntity();
        status = response.getStatusLine().getStatusCode();

        assertTrue("status is: " + status, status == 200);

        br = new BufferedReader(new InputStreamReader(entity.getContent()));
        line = br.readLine();

        assertNotNull(line);

        DNSRecordSet recordSet = mapper.readValue(line, DNSRecordSet.class);
        assertNotNull(recordSet);
        assertEquals(2, recordSet.getRecords().size());
        assertEquals("putin.porked.the.peace", recordSet.getFqdn());
        log.debug("record 1: " + recordSet.getRecords().get(0).toString());
        log.debug("record 2: " + recordSet.getRecords().get(1).toString());

        if (entity != null)
        {
            entity.getContent().close();
        }

    }

	private HttpRequestRetryHandler getMyRetryHandler()
	{
		//
		// set up a retry handler that overrides the default one, this one does not do any retries
		//
		return new HttpRequestRetryHandler()
		{
			public boolean retryRequest(
				IOException exception,
				int executionCount,
				HttpContext context)
			{
				return false;
			}
		};
	}

	private HttpResponse getResponse(Type type, boolean useAuth, String fqdn, DNSRecordSet r) throws Exception
	{
		String PATH_BASE = "/fqdn/1/";

		DefaultHttpClient httpClient = null;
		httpClient = new DefaultHttpClient(new BasicClientConnectionManager());
		httpClient.getParams().setParameter(AuthPNames.PROXY_AUTH_PREF, Arrays.asList(AuthPolicy.DIGEST));
		httpClient.setHttpRequestRetryHandler(getMyRetryHandler());

		if (useAuth)
		{
			httpClient.getCredentialsProvider().setCredentials
				(
					new AuthScope("127.0.0.1", 8181),
					new UsernamePasswordCredentials("foo", "bar")
				);
		}

		HttpHost httpHost = new HttpHost("127.0.0.1", 8181);

		String path = PATH_BASE + fqdn;

		switch (type)
		{
			case GET:
				return httpClient.execute(httpHost, new HttpGet(path));
			case POST:
				HttpPost post = new HttpPost(path);
				if (r != null)
				{
					post.setHeader("Content-Type", "application/json");
					post.setEntity(new StringEntity(r.toString()));
				}
				return httpClient.execute(httpHost, post);
			case DELETE:
				return httpClient.execute(httpHost, new HttpDelete(path));
		}

		return null;
	}

	/**
	 * stops and joins the jetty server and shuts down the database.
	 *
	 * @throws Exception on any errors.
	 */
	@AfterMethod
	public void stopServer() throws Exception
	{
		server.stop();
		server.join();
		db.shutdown();

		File f = new File(dbPath);

		if (f.exists())
		{
			FileUtils.deleteDirectory(f);
		}
	}
}
