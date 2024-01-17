/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rabbitmq.http.client

import com.rabbitmq.client.*
import com.rabbitmq.http.client.domain.*
import spock.lang.IgnoreIf
import spock.lang.Specification

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import static com.rabbitmq.http.client.domain.DestinationType.EXCHANGE
import static com.rabbitmq.http.client.domain.DestinationType.QUEUE

class ClientSpec extends Specification {

  static final String DEFAULT_USERNAME = "guest"

  static final String DEFAULT_PASSWORD = "guest"

  Client client

  String brokerVersion

  private final ConnectionFactory cf = initializeConnectionFactory()

  protected static ConnectionFactory initializeConnectionFactory() {
    def cf = new ConnectionFactory()
    cf.setAutomaticRecoveryEnabled(false)
    cf
  }

  def setup() {
    client = new Client(
            new ClientParameters().url(url()).username(DEFAULT_USERNAME).password(DEFAULT_PASSWORD)
                    .httpLayerFactory(JdkHttpClientHttpLayer.configure().create())
    )
    client.getConnections().each { client.closeConnection(it.getName())}
    awaitAllConnectionsClosed(client)
    brokerVersion = client.getOverview().getServerVersion()
  }

  static String url() {
    return "http://127.0.0.1:" + managementPort() + "/api/"
  }

  static int managementPort() {
    return System.getProperty("rabbitmq.management.port") == null ?
            15672 :
            Integer.valueOf(System.getProperty("rabbitmq.management.port"))
  }

  def "user info decoding"() {
    when: "username and password are encoded in the URL"
    def clientParameters = new ClientParameters()
            .url("http://test+user:test%40password@localhost:" + managementPort() + "/api/");
    then: "username and password are decoded"
    clientParameters.getUsername() == "test user"
    clientParameters.getPassword() == "test@password"
  }

  def "GET /api/overview"() {
    when: "client requests GET /api/overview"
    def conn = openConnection()
    def ch = conn.createChannel()
    1000.times { ch.basicPublish("", "", null, null) }

    def res = client.getOverview()
    def xts = res.getExchangeTypes().collect { it.getName() }

    then: "the response is converted successfully"
    res.getNode().startsWith("rabbit@")
    res.getErlangVersion() != null

    def msgStats = res.getMessageStats()
    msgStats.basicPublish >= 0
    msgStats.publisherConfirm >= 0
    msgStats.basicDeliver >= 0
    msgStats.basicReturn >= 0

    def qTotals = res.getQueueTotals()
    qTotals.messages >= 0
    qTotals.messagesReady >= 0
    qTotals.messagesUnacknowledged >= 0

    def oTotals = res.getObjectTotals()
    oTotals.connections >= 0
    oTotals.channels >= 0
    oTotals.exchanges >= 0
    oTotals.queues >= 0
    oTotals.consumers >= 0

    res.listeners.size() >= 1
    res.contexts.size() >= 1

    xts.contains("topic")
    xts.contains("fanout")
    xts.contains("direct")
    xts.contains("headers")

    cleanup:
    if (conn.isOpen()) {
      conn.close()
    }
  }

  def "GET /api/nodes"() {
    when: "client retrieves a list of cluster nodes"
    def res = client.getNodes()
    def node = res.first()

    then: "the list is returned"
    res.size() >= 1
    verifyNode(node)
  }

  def "GET /api/nodes/{name}"() {
    when: "client retrieves a list of cluster nodes"
    def res = client.getNodes()
    def name = res.first().name
    def node = client.getNode(name)

    then: "the list is returned"
    res.size() >= 1
    verifyNode(node)
  }

  
  def "GET /api/connections"() {
    given: "an open RabbitMQ client connection"
    def conn = openConnection()

    when: "client retrieves a list of connections"

    ConnectionInfo[] res = awaitEventPropagation({ client.getConnections() }) as ConnectionInfo[]
    def fst = res.first()

    then: "the list is returned"
    res.size() >= 1
    verifyConnectionInfo(fst)

    cleanup:
    conn.close()
  }

  
  def "GET /api/connections with paging"() {
    given: "some named RabbitMQ client connections"
    def connections = (0..15).collect {it ->
      def name = "list-connections-with-paging-test-" + it
      def conn = openConnection(name)
      conn
    }

    when: "client queries the first page of connections"
    waitAtMostUntilTrue(10, {
      client.getConnections().size() == connections.size()
    })
    def queryParameters = new QueryParameters()
            .pagination()
            .pageSize(10)
            .query()
    def page = client.getConnections(queryParameters)

    then: "a list of paged connections is returned"
    page.filteredCount == connections.size()
    page.itemCount == 10
    page.pageCount == 2
    page.totalCount >= page.filteredCount
    page.page == 1
    verifyConnectionInfo(page.itemsAsList.first())

    cleanup:
    connections.forEach { it.close() }

  }

  
  def "GET /api/connections/{name}"() {
    given: "an open RabbitMQ client connection"
    def conn = openConnection()

    when: "client retrieves connection info with the correct name"

    ConnectionInfo[] xs = awaitEventPropagation({ client.getConnections() }) as ConnectionInfo[]
    def x = client.getConnection(xs.first().name)

    then: "the info is returned"
    verifyConnectionInfo(x)

    cleanup:
    conn.close()
  }

  
  def "GET /api/connections/{name} with client-provided name"() {
    given: "an open RabbitMQ client connection with client-provided name"
    def s = UUID.randomUUID().toString()
    def conn = openConnection(s)

    when: "client retrieves connection info with the correct name"

    ConnectionInfo[] xs = awaitEventPropagation({ client.getConnections() }) as ConnectionInfo[]
    // applying filter as some previous connections can still show up the management API
    xs = xs.findAll({
      (it.clientProperties.connectionName == s)
    })
    def x = client.getConnection(xs.first().name)

    then: "the info is returned"
    verifyConnectionInfo(x)
    x.clientProperties.connectionName == s

    cleanup:
    conn.close()

  }

  def "GET /api/connections/username/{name}"() {
    if (!isVersion310orLater()) return
    given: "an open RabbitMQ client connection"
    def conn = openConnection()
    def username = "guest"

    when: "client retrieves user connection info for the given name"

    UserConnectionInfo[] xs = awaitEventPropagation({ client.getConnectionsOfUser(username) }) as UserConnectionInfo[]
    def uc = xs.first()

    then: "the info is returned"
    verifyUserConnectionInfo(uc, username)

    cleanup:
    if (isVersion310orLater()) {
      conn.close()
    }
  }

  def "DELETE /api/connections/username/{name}"() {
    if (!isVersion310orLater()) return
    given: "an open RabbitMQ client connection"
    def latch = new CountDownLatch(1)
    def s = UUID.randomUUID().toString()
    def username = "guest"
    def conn = openConnection(s)
    conn.addShutdownListener({ e -> latch.countDown()})
    assert conn.isOpen()

    and: "that shows up on connection list of a user"

    UserConnectionInfo[] xs = awaitEventPropagation({ client.getConnectionsOfUser(username) }) as UserConnectionInfo[]
    // applying filter as some previous connections can still show up the management API
    xs = xs.findAll({
      (it.username == username)
    })
    assert xs.length > 0

    when: "client closes all connections of the user"
    client.closeAllConnectionsOfUser(username);

    and: "some time passes"
    assert awaitOn(latch)

    then: "the connection is closed"
    !conn.isOpen()

    cleanup:
    if (isVersion310orLater() && conn.isOpen()) {
      conn.close()
    }
  }

  def "DELETE /api/connections/{name}"() {
    given: "an open RabbitMQ client connection"
    def latch = new CountDownLatch(1)
    def s = UUID.randomUUID().toString()
    def conn = openConnection(s)
    conn.addShutdownListener({ e -> latch.countDown()})
    assert conn.isOpen()

    when: "client closes the connection"

    ConnectionInfo[] xs = awaitEventPropagation({ client.getConnections() }) as ConnectionInfo[]
    // applying filter as some previous connections can still show up the management API
    xs = xs.findAll({
      (it.clientProperties.connectionName == s)
    })
    xs.each({ client.closeConnection(it.name) })

    and: "some time passes"
    assert awaitOn(latch)

    then: "the connection is closed"
    !conn.isOpen()

    cleanup:
    if (conn.isOpen()) {
      conn.close()
    }
  }

  def "DELETE /api/connections/{name} with a user-provided reason"() {
    given: "an open RabbitMQ client connection"
    def latch = new CountDownLatch(1)
    def s = UUID.randomUUID().toString()
    def conn = openConnection(s)
    conn.addShutdownListener({e -> latch.countDown()})
    assert conn.isOpen()

    when: "client closes the connection"

    ConnectionInfo[] xs = awaitEventPropagation({ client.getConnections() }) as ConnectionInfo[]
    // applying filter as some previous connections can still show up the management API
    xs = xs.findAll({
      (it.clientProperties.connectionName == s)
    })
    xs.each({ client.closeConnection(it.name, "because reasons!") })

    and: "some time passes"
    assert awaitOn(latch)

    then: "the connection is closed"
    !conn.isOpen()

    cleanup:
    if (conn.isOpen()) {
      conn.close()
    }

  }

  
  def "GET /api/channels"() {
    given: "an open RabbitMQ client connection with 1 channel"
    def s = UUID.randomUUID().toString()
    def conn = openConnection(s)
    def ch = conn.createChannel()

    when: "client lists channels"

    ConnectionInfo[] xs = awaitEventPropagation({ client.getConnections() }) as ConnectionInfo[]
    // applying filter as some previous connections can still show up the management API
    xs = xs.findAll({
      (it.clientProperties.connectionName == s)
    })
    def cn = xs.first().name
    ChannelInfo[] chs = awaitEventPropagation({ client.getChannels() }) as ChannelInfo[]
    // channel name starts with the connection name
    // e.g. 127.0.0.1:42590 -> 127.0.0.1:5672 (1)
    chs = chs.findAll({ it.name.startsWith(cn) })
    def chi = chs.first()

    then: "the list is returned"
    verifyChannelInfo(chi, ch)

    cleanup:
    if (conn.isOpen()) {
      conn.close()
    }

  }

  
  def "GET /api/channels with paging"() {
    given: "some AMQP channels"
    def conn = openConnection()
    def channels = (1..16).collect {it ->
        conn.createChannel(it)
    }

    when: "client queries the first page of channels"
    waitAtMostUntilTrue(10, {
      client.getChannels().size() == channels.size()
    })
    def queryParameters = new QueryParameters()
            .pagination()
            .pageSize(10)
            .query()
    def page = client.getChannels(queryParameters)

    then: "a list of paged channels is returned"
    page.filteredCount == channels.size()
    page.itemCount == 10
    page.pageCount == 2
    page.totalCount >= page.filteredCount
    page.page == 1
    def channelInfo = page.itemsAsList.first()
    def originalChannel = channels.find {it.getChannelNumber() == channelInfo.getNumber() }
    verifyChannelInfo(channelInfo, originalChannel)

    cleanup:
    conn.close()

  }

  
  def "GET /api/connections/{name}/channels/"() {
    given: "an open RabbitMQ client connection with 1 channel"
    def s = UUID.randomUUID().toString()
    def conn = openConnection(s)
    def ch = conn.createChannel()

    when: "client lists channels on that connection"

    ConnectionInfo[] xs = awaitEventPropagation({ client.getConnections() }) as ConnectionInfo[]
    // applying filter as some previous connections can still show up the management API
    xs = xs.findAll({
      (it.clientProperties.connectionName == s)
    })
    def cn = xs.first().name

    ChannelInfo[] chs = awaitEventPropagation({ client.getChannels(cn) }) as ChannelInfo[]
    def chi = chs.first()

    then: "the list is returned"
    verifyChannelInfo(chi, ch)

    cleanup:
    if (conn.isOpen()) {
      conn.close()
    }

  }

  
  def "GET /api/channels/{name}"() {
    given: "an open RabbitMQ client connection with 1 channel"
    def s = UUID.randomUUID().toString()
    def conn = openConnection(s)
    def ch = conn.createChannel()

    when: "client retrieves channel info"

    ConnectionInfo[] xs = awaitEventPropagation({ client.getConnections() }) as ConnectionInfo[]
    // applying filter as some previous connections can still show up the management API
    xs = xs.findAll({
      (it.clientProperties.connectionName == s)
    })
    def cn = xs.first().name
    ChannelInfo[] chs = awaitEventPropagation({ client.getChannels(cn) }) as ChannelInfo[]
    def chi = client.getChannel(chs.first().name)

    then: "the info is returned"
    verifyChannelInfo(chi, ch)

    cleanup:
    if (conn.isOpen()) {
      conn.close()
    }

  }

  
  def "GET /api/exchanges"() {
    when: "client retrieves the list of exchanges across all vhosts"
    def xs = client.getExchanges()
    def x = xs.first()

    then: "the list is returned"
    verifyExchangeInfo(x)

  }

  
  def "GET /api/exchanges with paging"() {
    given: "at least one exchange was declared"

    when: "client lists exchanges"
    def queryParameters = new QueryParameters().pagination().pageSize(10).query();
    def pagedXs = client.getExchanges("/", queryParameters)

    then: "a list of paged exchanges is returned"
    def x = pagedXs.itemsAsList.find { (it.name == "amq.fanout") }
    verifyExchangeInfo(x)

  }

  
  def "GET /api/exchanges/{vhost} when vhost exists"() {
    when: "client retrieves the list of exchanges in a particular vhost"
    def xs = client.getExchanges("/")

    then: "the list is returned"
    def x = xs.find { (it.name == "amq.fanout") }
    verifyExchangeInfo(x)

  }

  
  def "GET /api/exchanges/{vhost} when vhost DOES NOT exist"() {
    given: "vhost lolwut does not exist"
    def v = "lolwut"
    client.deleteVhost(v)

    when: "client retrieves the list of exchanges in that vhost"
    def xs = client.getExchanges(v)

    then: "null is returned"
    xs == null

  }

  
  def "GET /api/exchanges/{vhost}/{name} when both vhost and exchange exist"() {
    when: "client retrieves exchange amq.fanout in vhost /"
    def xs = client.getExchange("/", "amq.fanout")

    then: "exchange info is returned"
    ExchangeInfo x = (ExchangeInfo)xs.find { it.name == "amq.fanout" && it.vhost == "/" }
    verifyExchangeInfo(x)

  }

  
  def "PUT /api/exchanges/{vhost}/{name} when vhost exists"() {
    given: "fanout exchange hop.test in vhost /"
    def v = "/"
    def s = "hop.test"
    client.declareExchange(v, s, new ExchangeInfo("fanout", false, false))

    when: "client lists exchanges in vhost /"
    List<ExchangeInfo> xs = client.getExchanges(v)

    then: "hop.test is listed"
    ExchangeInfo x = xs.find { (it.name == s) }
    x != null
    verifyExchangeInfo(x)

    cleanup:
    client.deleteExchange(v, s)

  }

  
  def "DELETE /api/exchanges/{vhost}/{name}"() {
    given: "fanout exchange hop.test in vhost /"
    def v = "/"
    def s = "hop.test"
    client.declareExchange(v, s, new ExchangeInfo("fanout", false, false))

    List<ExchangeInfo> xs = client.getExchanges(v)
    ExchangeInfo x = xs.find { (it.name == s) }
    x != null
    verifyExchangeInfo(x)

    when: "client deletes exchange hop.test in vhost /"
    client.deleteExchange(v, s)

    and: "exchange list in / is reloaded"
    xs = client.getExchanges(v)

    then: "hop.test no longer exists"
    xs.find { (it.name == s) } == null

  }

  
  def "POST /api/exchanges/{vhost}/{name}/publish"() {
    given: "a queue named hop.publish and a consumer on this queue"
    def v = "/"
    def conn = openConnection()
    def ch = conn.createChannel()
    def q = "hop.publish"
    ch.queueDeclare(q, false, false, false, null)
    ch.queueBind(q, "amq.direct", q)
    def latch = new CountDownLatch(1)
    def payloadReference = new AtomicReference<String>()
    def propertiesReference = new AtomicReference<AMQP.BasicProperties>()
    ch.basicConsume(q, true, {ctag, message ->
      payloadReference.set(new String(message.getBody()))
      propertiesReference.set(message.getProperties())
      latch.countDown()
    }, { ctag -> } as CancelCallback)

    when: "client publishes a message to the queue"
    def properties = new HashMap()
    properties.put("delivery_mode", 1)
    properties.put("content_type", "text/plain")
    properties.put("priority", 5)
    properties.put("headers", Collections.singletonMap("header1", "value1"))
    def routed = client.publish(v, "amq.direct", q,
            new OutboundMessage().payload("Hello world!").utf8Encoded().properties(properties))

    then: "the message is routed to the queue and consumed"
    routed
    latch.await(5, TimeUnit.SECONDS)
    payloadReference.get() == "Hello world!"
    propertiesReference.get().getDeliveryMode() == 1
    propertiesReference.get().getContentType() == "text/plain"
    propertiesReference.get().getPriority() == 5
    propertiesReference.get().getHeaders() != null
    propertiesReference.get().getHeaders().size() == 1
    propertiesReference.get().getHeaders().get("header1").toString() == "value1"

    cleanup:
    ch.queueDelete(q)
    conn.close()

  }

  
  def "GET /api/exchanges/{vhost}/{name}/bindings/source"() {
    given: "a queue named hop.queue1"
    def conn = openConnection()
    def ch = conn.createChannel()
    def q = "hop.queue1"
    ch.queueDeclare(q, false, false, false, null)

    when: "client lists bindings of default exchange"
    def xs = client.getBindingsBySource("/", "")

    then: "there is an automatic binding for hop.queue1"
    def x = xs.find { it.source == "" && it.destinationType == QUEUE && it.destination == q }
    x != null

    cleanup:
    ch.queueDelete(q)
    conn.close()

  }

  
  def "GET /api/exchanges/{vhost}/{name}/bindings/destination"() {
    given: "an exchange named hop.exchange1 which is bound to amq.fanout"
    def conn = openConnection()
    def ch = conn.createChannel()
    def src = "amq.fanout"
    def dest = "hop.exchange1"
    ch.exchangeDeclare(dest, "fanout")
    ch.exchangeBind(dest, src, "")

    when: "client lists bindings of amq.fanout"
    def xs = client.getExchangeBindingsByDestination("/", dest)

    then: "there is a binding for hop.exchange1"
    def x = xs.find { it.source == src &&
        it.destinationType == EXCHANGE &&
        it.destination == dest
    }
    x != null

    cleanup:
    ch.exchangeDelete(dest)
    conn.close()

  }

  
  def "GET /api/queues"() {
    given: "at least one queue was declared"
    Connection conn = cf.newConnection()
    Channel ch = conn.createChannel()
    String q = ch.queueDeclare().queue

    when: "client lists queues"
    def xs = client.getQueues()

    then: "a list of queues is returned"
    def x = xs.first()
    verifyQueueInfo(x)

    cleanup:
    ch.queueDelete(q)
    conn.close()

  }

  
  def "GET /api/queues with details"() {
    given: "at least one queue was declared and some messages published"
    Connection conn = cf.newConnection()
    Channel ch = conn.createChannel()
    String q = ch.queueDeclare().queue
    ExecutorService executorService = Executors.newSingleThreadExecutor()
    def publishing = {
      while (!Thread.currentThread().isInterrupted()) {
        ch.basicPublish("", q, null, "".getBytes(StandardCharsets.UTF_8))
        try {
          Thread.sleep(10)
        } catch (InterruptedException e) {
          return
        }
      }
    }
    executorService.submit(publishing)

    when: "client lists queues with details"
    def detailsParameters = new DetailsParameters()
      .messageRates(60, 5)
      .lengths(60, 5)
    def request = { client.getQueues(detailsParameters)
      .find({qi -> qi.name == q})}
    waitAtMostUntilTrue(10, {
      def qi = request()
      qi.messagesDetails != null && qi.messagesDetails.rate > 0
    })
    def x = request()

    then: "a list of queues with details is returned"
    verifyQueueInfo(x)
    x.getMessagesDetails() != null
    x.getMessagesDetails().getAverage() > 0
    x.getMessagesDetails().getAverageRate() > 0
    x.getMessagesDetails().getSamples().size() > 0
    x.getMessagesDetails().getSamples().get(0).getSample() > 0
    x.getMessagesDetails().getSamples().get(0).getTimestamp() > 0

    cleanup:
    executorService.shutdownNow()
    ch.queueDelete(q)
    conn.close()

  }

  
  def "GET /api/queues with paging"() {
    given: "at least one queue was declared"
    Connection conn = cf.newConnection()
    Channel ch = conn.createChannel()
    def queues = (0..15).collect {it ->
      def q = "queue-for-paging-test-" + it
      ch.queueDeclare(q, false, false, false, null)
      q
    }

    when: "client lists queues"
    def queryParameters = new QueryParameters()
            .name("^queue-for-paging-test-*", true)
            .pagination()
            .pageSize(10)
            .query()
    def page = client.getQueues(queryParameters)

    then: "a list of paged queues is returned"
    page.filteredCount == queues.size()
    page.itemCount == 10
    page.pageCount == 2
    page.totalCount >= page.filteredCount
    page.page == 1
    def x = page.itemsAsList.first();
    verifyQueueInfo(x)

    cleanup:
    queues.forEach { ch.queueDelete(it) }
    conn.close()

  }

  
  def "GET /api/queues with paging and navigating"() {
    given: "at least one queue was declared"
    Connection conn = cf.newConnection()
    Channel ch = conn.createChannel()
    def queues = (0..15).collect {it ->
      def q = "queue-for-paging-and-navigating-test-" + it
      ch.queueDeclare(q, false, false, false, null)
      q
    }

    when: "client gets first page and then second page"
    def queryParameters = new QueryParameters()
            .name("^queue-for-paging-and-navigating-test-*", true)
            .pagination()
            .pageSize(10)
            .query()
    def page = client.getQueues(queryParameters)
    page = client.getQueues(queryParameters.pagination().nextPage(page).query())

    then: "the second page is returned"
    page.filteredCount == queues.size()
    page.itemCount == 6
    page.pageCount == 2
    page.totalCount >= page.filteredCount
    page.page == 2
    def x = page.itemsAsList.first();
    verifyQueueInfo(x)

    cleanup:
    queues.forEach { ch.queueDelete(it) }
    conn.close()

  }

  
  def "GET /api/queues with paging and details"() {
    given: "at least one queue was declared"
    Connection conn = cf.newConnection()
    Channel ch = conn.createChannel()
    def queues = (0..15).collect {it ->
      def q = "queue-for-paging-and-details-test-" + it
      ch.queueDeclare(q, false, false, false, null)
      ch.queueBind(q, "amq.fanout", "")
      q
    }

    ExecutorService executorService = Executors.newSingleThreadExecutor()
    def publishing = {
      while (!Thread.currentThread().isInterrupted()) {
        ch.basicPublish("amq.fanout", "", null, "".getBytes(StandardCharsets.UTF_8))
        try {
          Thread.sleep(10)
        } catch (InterruptedException e) {
          return
        }
      }
    }
    executorService.submit(publishing)

    when: "client lists queues with details"
    def queryParameters = new DetailsParameters()
              .messageRates(60, 5)
              .lengths(60, 5)
            .queryParameters()
              .name("^queue-for-paging-and-details-test-*", true)
              .pagination()
              .pageSize(10)
            .query()

    def request = { client.getQueues(queryParameters) }
    waitAtMostUntilTrue(10, {
      def p = request()
      p.filteredCount == queues.size() && p.items[0].messagesDetails != null
        && p.items[0].messagesDetails.rate > 0
    })
    def page = request()

    then: "a list of paged queues with details is returned"
    page.filteredCount == queues.size()
    page.itemCount == 10
    page.pageCount == 2
    page.totalCount >= page.filteredCount
    page.page == 1
    def x = page.itemsAsList.first();
    verifyQueueInfo(x)
    x.getMessagesDetails() != null
    x.getMessagesDetails().getAverage() > 0
    x.getMessagesDetails().getAverageRate() > 0
    x.getMessagesDetails().getSamples().size() > 0
    x.getMessagesDetails().getSamples().get(0).getSample() > 0
    x.getMessagesDetails().getSamples().get(0).getTimestamp() > 0

    cleanup:
    executorService.shutdownNow()
    queues.forEach { ch.queueDelete(it) }
    conn.close()

  }

  
  def "GET /api/queues/{vhost} when vhost exists"() {
    given: "at least one queue was declared in vhost /"
    Connection conn = cf.newConnection()
    Channel ch = conn.createChannel()
    String q = ch.queueDeclare().queue

    when: "client lists queues"
    def xs = client.getQueues("/")

    then: "a list of queues is returned"
    def x = xs.first()
    verifyQueueInfo(x)

    cleanup:
    ch.queueDelete(q)
    conn.close()

  }

  
  def "GET /api/queues/{vhost} when vhost DOES NOT exist"() {
    given: "vhost lolwut DOES not exist"
    def v = "lolwut"
    client.deleteVhost(v)

    when: "client lists queues"
    def xs = client.getQueues(v)

    then: "null is returned"
    xs == null

  }

  
  def "GET /api/queues/{vhost}/{name} when both vhost and queue exist"() {
    given: "a queue was declared in vhost /"
    Connection conn = cf.newConnection()
    Channel ch = conn.createChannel()
    String q = ch.queueDeclare().queue

    when: "client fetches info of the queue"
    def x = client.getQueue("/", q)

    then: "the info is returned"
    x.vhost == "/"
    x.name == q
    verifyQueueInfo(x)

    cleanup:
    ch.queueDelete(q)
    conn.close()

  }

  
  def "GET /api/queues/{vhost}/{name} with an exclusive queue"() {
    given: "an exclusive queue named hop.q1.exclusive"
    Connection conn = cf.newConnection()
    Channel ch = conn.createChannel()
    String s = "hop.q1.exclusive"
    ch.queueDelete(s)
    String q = ch.queueDeclare(s, false, true, false, null).queue

    when: "client fetches info of the queue"
    def x = client.getQueue("/", q)

    then: "the queue is exclusive according to the response"
    x.exclusive

    cleanup:
    ch.queueDelete(q)
    conn.close()

  }

  
  def "GET /api/queues/{name} with details"() {
    given: "at least one queue was declared and some messages published"
    ExecutorService executorService = Executors.newSingleThreadExecutor()
    Connection conn = cf.newConnection()
    Channel ch = conn.createChannel()
    String q = ch.queueDeclare().queue
    def publishing = {
      while (!Thread.currentThread().isInterrupted()) {
        ch.basicPublish("", q, null, "".getBytes(StandardCharsets.UTF_8))
        try {
          Thread.sleep(10)
        } catch (InterruptedException e) {
          return
        }
      }
    }
    executorService.submit(publishing)

    when: "client get queue with details"
    def request = { client.getQueue("/", q, new DetailsParameters()
            .messageRates(60, 5)
            .lengths(60, 5))}
    waitAtMostUntilTrue(10, {
      def qi = request()
      qi.getMessagesDetails() != null && qi.getMessagesDetails().getRate() > 0
    })
    def x = request()

    then: "the queue with details info is returned"
    verifyQueueInfo(x)
    x.getMessagesDetails() != null
    x.getMessagesDetails().getAverage() > 0
    x.getMessagesDetails().getAverageRate() > 0
    x.getMessagesDetails().getSamples().size() > 0
    x.getMessagesDetails().getSamples().get(0).getSample() > 0
    x.getMessagesDetails().getSamples().get(0).getTimestamp() > 0

    x.getMessagesReadyDetails() != null
    x.getMessagesReadyDetails().getAverage() > 0
    x.getMessagesReadyDetails().getAverageRate() > 0
    x.getMessagesReadyDetails().getSamples().size() > 0
    x.getMessagesReadyDetails().getSamples().get(0).getSample() > 0
    x.getMessagesReadyDetails().getSamples().get(0).getTimestamp() > 0

    x.getMessagesUnacknowledgedDetails() != null

    x.getMessageStats() != null
    x.getMessageStats().getBasicPublishDetails().getAverage() > 0
    x.getMessageStats().getBasicPublishDetails().getAverageRate() > 0
    x.getMessageStats().getBasicPublishDetails().getSamples().size() > 0
    x.getMessageStats().getBasicPublishDetails().getSamples().get(0).getSample() > 0
    x.getMessageStats().getBasicPublishDetails().getSamples().get(0).getTimestamp() > 0

    cleanup:
    executorService.shutdown()
    ch.queueDelete(q)
    conn.close()

  }

  
  def "GET /api/queues/{vhost}/{name} when queue DOES NOT exist"() {
    given: "queue lolwut does not exist in vhost /"
    def Connection conn = cf.newConnection()
    def Channel ch = conn.createChannel()
    def String q = "lolwut"
    ch.queueDelete(q)

    when: "client fetches info of the queue"
    def x = client.getQueue("/", q)

    then: "null is returned"
    x == null

    cleanup:
    ch.queueDelete(q)
    conn.close()

  }

  
  def "PUT /api/queues/{vhost}/{name} when vhost exists"() {
    given: "vhost /"
    def v = "/"

    when: "client declares a queue hop.test"
    def s = "hop.test"
    client.declareQueue(v, s, new QueueInfo(false, false, false))

    and: "client lists queues in vhost /"
    List<QueueInfo> xs = client.getQueues(v)

    then: "hop.test is listed"
    QueueInfo x = xs.find { (it.name == s) }
    x != null
    x.vhost == v
    x.name == s
    !x.durable
    !x.exclusive
    !x.autoDelete

    cleanup:
    client.deleteQueue(v, s)

  }

  //
  // Consumers
  //

  
  def "GET /api/consumers"() {
    given: "at least one queue with an online consumer"
    Connection conn = cf.newConnection()
    Channel ch = conn.createChannel()
    String q = ch.queueDeclare().queue
    String consumerTag = ch.basicConsume(q, true, {_ctag, _msg ->
      // do nothing
    }, { _ctag -> } as CancelCallback)
    awaitEventPropagation {}

    when: "client lists consumers"
    def cs = awaitEventPropagation { client.getConsumers() } as ConsumerDetails[]

    then: "a list of consumers is returned"
    ConsumerDetails cons = cs.find { it.consumerTag == consumerTag }
    cons != null

    cleanup:
    ch.queueDelete(q)
    conn.close()

  }

  
  def "GET /api/consumers/{vhost}"() {
    given: "at least one queue with an online consumer in a given virtual host"
    Connection conn = cf.newConnection()
    Channel ch = conn.createChannel()
    String q = ch.queueDeclare().queue
    String consumerTag = ch.basicConsume(q, true, {_ctag, _msg ->
      // do nothing
    }, { _ctag -> } as CancelCallback)
    waitAtMostUntilTrue(10, {
      client.getConsumers().size() == 1
    })

    when: "client lists consumers in a specific virtual host"
    def cs = awaitEventPropagation { client.getConsumers("/") } as ConsumerDetails[]

    then: "a list of consumers in that virtual host is returned"
    ConsumerDetails cons = cs.find { it.consumerTag == consumerTag }
    cons != null

    cleanup:
    ch.queueDelete(q)
    conn.close()

  }

  
  def "GET /api/consumers/{vhost} with no consumers in vhost"() {
    given: "at least one queue with an online consumer in a given virtual host"
    Connection conn = cf.newConnection()
    Channel ch = conn.createChannel()
    String q = ch.queueDeclare().queue
    ch.basicConsume(q, true, {_ctag, _msg ->
      // do nothing
    }, { _ctag -> } as CancelCallback)
    waitAtMostUntilTrue(10, {
      client.getConsumers().size() == 1
    })

    when: "client lists consumers in another virtual host "
    def cs = client.getConsumers("vh1")

    then: "no consumers are returned"
    cs.isEmpty()

    cleanup:
    ch.queueDelete(q)
    conn.close()

  }

  //
  // Policies
  //

  
  def "PUT /api/policies/{vhost}/{name}"() {
    given: "vhost / and definition"
    def v = "/"
    def d = new HashMap<String, Object>()
    d.put("ha-mode", "all")

    when: "client declares a policy hop.test"
    def s = "hop.test"
    client.declarePolicy(v, s, new PolicyInfo(".*", 1, null, d))

    and: "client lists policies in vhost /"
    List<PolicyInfo> ps = client.getPolicies(v)

    then: "hop.test is listed"
    PolicyInfo p = ps.find { (it.name == s) }
    p != null
    p.vhost == v
    p.name == s
    p.priority == 1
    p.applyTo == "all"
    p.definition == d

    cleanup:
    client.deletePolicy(v, s)

  }

  def "PUT /api/operator-policies/{vhost}/{name}"() {
    given: "vhost / and definition"
    def v = "/"
    def d = new HashMap<String, Object>()
    d.put("max-length", 6)

    when: "client declares an operator policy hop.test"
    def s = "hop.test"
    client.declareOperatorPolicy(v, s, new PolicyInfo(".*", 1, null, d))

    and: "client lists operator policies in vhost /"
    List<PolicyInfo> ps = client.getOperatorPolicies(v)

    then: "hop.test is listed"
    PolicyInfo p = ps.find { (it.name == s) }
    p != null
    p.vhost == v
    p.name == s
    p.priority == 1
    p.applyTo == "all"
    p.definition == d

    cleanup:
    client.deleteOperatorPolicy(v, s)

  }

  
  def "PUT /api/queues/{vhost}/{name} when vhost DOES NOT exist"() {
    given: "vhost lolwut which does not exist"
    def v = "lolwut"
    client.deleteVhost(v)

    when: "client declares a queue hop.test"
    def s = "hop.test"
    client.declareQueue(v, s, new QueueInfo(false, false, false))

    then: "an exception is thrown"
    def e = thrown(Exception)
    exceptionStatus(e) == 404

  }

  
  def "DELETE /api/queues/{vhost}/{name}"() {
    def String s = UUID.randomUUID().toString()
    given: "queue ${s} in vhost /"
    def v = "/"
    client.declareQueue(v, s, new QueueInfo(false, false, false))

    List<QueueInfo> xs = client.getQueues(v)
    QueueInfo x = xs.find { (it.name == s) }
    x != null
    verifyQueueInfo(x)

    when: "client deletes queue ${s} in vhost /"
    client.deleteQueue(v, s)

    and: "queue list in / is reloaded"
    xs = client.getQueues(v)

    then: "${s} no longer exists"
    xs.find { (it.name == s) } == null

  }

  
  def "DELETE /api/queues/{vhost}/{name}?if-empty=true"() {
    def String queue = UUID.randomUUID().toString()
    given: "queue ${queue} in vhost /"
    def v = "/"
    client.declareQueue(v, queue, new QueueInfo(false, false, false))

    List<QueueInfo> xs = client.getQueues(v)
    QueueInfo x = xs.find { (it.name == queue) }
    x != null
    verifyQueueInfo(x)

    and: "queue has a message"
    client.publish(v, "amq.default", queue, new OutboundMessage().payload("test"))

    when: "client tries to delete queue ${queue} in vhost /"
    client.deleteQueue(v, queue, new DeleteQueueParameters(true, false))

    then: "an exception is thrown"
    def e = thrown(Exception)
    exceptionStatus(e) == 400

    cleanup:
    client.deleteQueue(v, queue)

  }

  
  def "GET /api/bindings"() {
    given: "3 queues bound to amq.fanout"
    def Connection conn = cf.newConnection()
    def Channel ch = conn.createChannel()
    def String x  = 'amq.fanout'
    def String q1 = ch.queueDeclare().queue
    def String q2 = ch.queueDeclare().queue
    def String q3 = ch.queueDeclare().queue
    ch.queueBind(q1, x, "")
    ch.queueBind(q2, x, "")
    ch.queueBind(q3, x, "")

    when: "all queue bindings are listed"
    def List<BindingInfo> xs = client.getBindings()

    then: "amq.fanout bindings are listed"
    xs.findAll { it.destinationType == QUEUE && it.source == x }
      .size() >= 3

    cleanup:
    ch.queueDelete(q1)
    ch.queueDelete(q2)
    ch.queueDelete(q3)
    conn.close()

  }

  
  def "GET /api/bindings/{vhost}"() {
    given: "2 queues bound to amq.topic in vhost /"
    def Connection conn = cf.newConnection()
    def Channel ch = conn.createChannel()
    def String x  = 'amq.topic'
    def String q1 = ch.queueDeclare().queue
    def String q2 = ch.queueDeclare().queue
    ch.queueBind(q1, x, "hop.*")
    ch.queueBind(q2, x, "api.test.#")

    when: "all queue bindings are listed"
    def List<BindingInfo> xs = client.getBindings("/")

    then: "amq.fanout bindings are listed"
    xs.findAll { it.destinationType == QUEUE && it.source == x }
      .size() >= 2

    cleanup:
    ch.queueDelete(q1)
    ch.queueDelete(q2)
    conn.close()

  }

  
  def "GET /api/bindings/{vhost} example 2"() {
    given: "queues hop.test bound to amq.topic in vhost /"
    def Connection conn = cf.newConnection()
    def Channel ch = conn.createChannel()
    def String x  = 'amq.topic'
    def String q  = "hop.test"
    ch.queueDeclare(q, false, false, false, null)
    ch.queueBind(q, x, "hop.*")

    when: "all queue bindings are listed"
    def List<BindingInfo> xs = client.getBindings("/")

    then: "the amq.fanout binding is listed"
    xs.find { it.destinationType == QUEUE && it.source == x && it.destination == q }

    cleanup:
    ch.queueDelete(q)
    conn.close()

  }

  
  def "GET /api/queues/{vhost}/{name}/bindings"() {
    given: "queues hop.test bound to amq.topic in vhost /"
    def Connection conn = cf.newConnection()
    def Channel ch = conn.createChannel()
    def String x  = 'amq.topic'
    def String q  = "hop.test"
    ch.queueDeclare(q, false, false, false, null)
    ch.queueBind(q, x, "hop.*")

    when: "all queue bindings are listed"
    def List<BindingInfo> xs = client.getQueueBindings("/", q)

    then: "the amq.fanout binding is listed"
    xs.find { it.destinationType == QUEUE && it.source == x && it.destination == q }

    cleanup:
    ch.queueDelete(q)
    conn.close()

  }

  
  def "GET /api/bindings/{vhost}/e/:exchange/q/:queue"() {
    given: "queues hop.test bound to amq.topic in vhost /"
    def Connection conn = cf.newConnection()
    def Channel ch = conn.createChannel()
    def String x  = 'amq.topic'
    def String q  = "hop.test"
    ch.queueDeclare(q, false, false, false, null)
    ch.queueBind(q, x, "hop.*")

    when: "bindings between hop.test and amq.topic are listed"
    def List<BindingInfo> xs = client.getQueueBindingsBetween("/", x, q)

    then: "the amq.fanout binding is listed"
    def b = xs.find()
    xs.size() == 1
    b.source == x
    b.destination == q
    b.destinationType == QUEUE

    cleanup:
    ch.queueDelete(q)
    conn.close()

  }

  
  def "GET /api/bindings/{vhost}/e/:source/e/:destination"() {
    given: "fanout exchange hop.test bound to amq.fanout in vhost /"
    def Connection conn = cf.newConnection()
    def Channel ch = conn.createChannel()
    def String s  = 'amq.fanout'
    def String d  = "hop.test"
    ch.exchangeDeclare(d, "fanout", false)
    ch.exchangeBind(d, s, "")

    when: "bindings between hop.test and amq.topic are listed"
    def List<BindingInfo> xs = client.getExchangeBindingsBetween("/", s, d)

    then: "the amq.topic binding is listed"
    def b = xs.find()
    xs.size() == 1
    b.source == s
    b.destination == d
    b.destinationType == EXCHANGE

    cleanup:
    ch.exchangeDelete(d)
    conn.close()

  }

  
  def "POST /api/bindings/{vhost}/e/:source/e/:destination"() {
    given: "fanout hop.test bound to amq.fanout in vhost /"
    def v = "/"
    def String s  = 'amq.fanout'
    def String d  = "hop.test"
    client.deleteExchange(v, d)
    client.declareExchange(v, d, new ExchangeInfo("fanout", false, false))
    client.bindExchange(v, d, s, "", [arg1: 'value1', arg2: 'value2'])

    when: "bindings between hop.test and amq.fanout are listed"
    def List<BindingInfo> xs = client.getExchangeBindingsBetween(v, s, d)

    then: "the amq.fanout binding is listed"
    def b = xs.find()
    xs.size() == 1
    b.source == s
    b.destination == d
    b.destinationType == EXCHANGE
    b.arguments.size() == 2
    b.arguments.containsKey("arg1")
    b.arguments.arg1 == "value1"
    b.arguments.containsKey("arg2")
    b.arguments.arg2 == "value2"

    cleanup:
    client.deleteExchange(v, d)

  }

  
  def "POST /api/bindings/{vhost}/e/:exchange/q/:queue"() {
    given: "queues hop.test bound to amq.topic in vhost /"
    def v = "/"
    def String x  = 'amq.topic'
    def String q  = "hop.test"
    client.declareQueue(v, q, new QueueInfo(false, false, false))
    client.bindQueue(v, q, x, "", [arg1: 'value1', arg2: 'value2'])

    when: "bindings between hop.test and amq.topic are listed"
    def List<BindingInfo> xs = client.getQueueBindingsBetween(v, x, q)

    then: "the amq.fanout binding is listed"
    def b = xs.find()
    xs.size() == 1
    b.source == x
    b.destination == q
    b.destinationType == QUEUE
    b.arguments.size() == 2
    b.arguments.containsKey("arg1")
    b.arguments.arg1 == "value1"
    b.arguments.containsKey("arg2")
    b.arguments.arg2 == "value2"

    cleanup:
    client.deleteQueue(v, q)

  }

//  def "GET /api/bindings/{vhost}/e/:exchange/q/:queue/props"() {
    // TODO
//  }

//  def "DELETE /api/bindings/{vhost}/e/:exchange/q/:queue/props"() {
    // TODO
//  }

  
  def "POST /api/queues/{vhost}/:exchange/get"() {
    given: "a queue named hop.get and some messages in this queue"
    def v = "/"
    def conn = openConnection()
    def ch = conn.createChannel()
    def q = "hop.get"
    ch.queueDeclare(q, false, false, false, null)
    ch.confirmSelect()
    def messageCount = 5
    def properties = new AMQP.BasicProperties.Builder()
            .contentType("text/plain").deliveryMode(1).priority(5)
            .headers(Collections.singletonMap("header1", "value1"))
            .build()
    (1..messageCount).each { it ->
      ch.basicPublish("", q, properties, "payload${it}".getBytes(Charset.forName("UTF-8")))
    }
    ch.waitForConfirms(5_000)

    when: "client GETs from this queue"
    def List<InboundMessage> messages = client.get(v, q, messageCount, GetAckMode.NACK_REQUEUE_TRUE, GetEncoding.AUTO, -1)

    then: "the messages are returned"
    messages.size() == messageCount
    def message = messages.get(0)
    message.payload.startsWith("payload")
    message.payloadBytes == "payload".size() + 1
    !message.redelivered
    message.routingKey == q
    message.payloadEncoding == "string"
    message.properties != null
    message.properties.size() == 4
    message.properties.get("priority") == 5
    message.properties.get("delivery_mode") == 1
    message.properties.get("content_type") == "text/plain"
    message.properties.get("headers") != null
    message.properties.get("headers").size() == 1
    message.properties.get("headers").get("header1") == "value1"

    cleanup:
    ch.queueDelete(q)
    conn.close()

  }

  
  def "POST /api/queues/{vhost}/:exchange/get for one message"() {
    given: "a queue named hop.get and a message in this queue"
    def v = "/"
    def conn = openConnection()
    def ch = conn.createChannel()
    def q = "hop.get"
    ch.queueDeclare(q, false, false, false, null)
    ch.confirmSelect()
    ch.basicPublish("", q, null, "payload".getBytes(Charset.forName("UTF-8")))
    ch.waitForConfirms(5_000)

    when: "client GETs from this queue"
    def message = client.get(v, q)

    then: "the messages are returned"
    message.payload == "payload"
    message.payloadBytes == "payload".size()
    !message.redelivered
    message.routingKey == q
    message.payloadEncoding == "string"

    cleanup:
    ch.queueDelete(q)
    conn.close()

  }

  
  def "DELETE /api/queues/{vhost}/{name}/contents"() {
    given: "queue hop.test with 10 messages"
    def Connection conn = cf.newConnection()
    def Channel ch = conn.createChannel()
    def q = "hop.test"
    ch.queueDelete(q)
    ch.queueDeclare(q, false, false, false, null)
    ch.queueBind(q, "amq.fanout", "")
    ch.confirmSelect()
    100.times { ch.basicPublish("amq.fanout", "", null, "msg".getBytes()) }
    assert ch.waitForConfirms()
    def qi1 = ch.queueDeclarePassive(q)
    qi1.messageCount == 100

    when: "client purges the queue"
    client.purgeQueue("/", q)

    then: "the queue becomes empty"
    def qi2 = ch.queueDeclarePassive(q)
    qi2.messageCount == 0

    cleanup:
    ch.queueDelete(q)
    conn.close()

  }

//  def "POST /api/queues/{vhost}/{name}/get"() {
    // TODO
//  }

  
  def "GET /api/vhosts"() {
    when: "client retrieves a list of vhosts"
    def vhs = client.getVhosts()
    def vhi = vhs.first()

    then: "the info is returned"
    verifyVhost(vhi, client.getOverview().getServerVersion())

  }

  
  def "GET /api/vhosts/{name}"() {
    when: "client retrieves vhost info"
    def vhi = client.getVhost("/")

    then: "the info is returned"
    verifyVhost(vhi, client.getOverview().getServerVersion())

  }

  @IgnoreIf({ os.windows })
  def "PUT /api/vhosts/{name}"() {
    when:
    "client creates a vhost named $name"
    client.createVhost(name)
    def vhi = client.getVhost(name)

    then: "the vhost is created"
    vhi.name == name

    cleanup:
    client.deleteVhost(name)

    where:
    name << [
            "http-created",
            "http-created2",
            "http_created",
            "http created",
            "создан по хатэтэпэ",
            "creado a través de HTTP",
            "通过http",
            "HTTP를 통해 생성",
            "HTTPを介して作成",
            "created over http?",
            "created @ http API",
            "erstellt über http",
            "http पर बनाया",
            "ถูกสร้างขึ้นผ่าน HTTP",
            "±!@^&#*"
    ]
  }

  static def clientsAndVhosts() {
      [
              "http-created",
              "http-created2",
              "http_created",
              "http created",
              "создан по хатэтэпэ",
              "creado a través de HTTP",
              "通过http",
              "HTTP를 통해 생성",
              "HTTPを介して作成",
              "created over http?",
              "created @ http API",
              "erstellt über http",
              "http पर बनाया",
              "ถูกสร้างขึ้นผ่าน HTTP",
              "±!@^&#*"
      ]
  }

  
  def "PUT /api/vhosts/{name} with metadata"() {
    if (!isVersion38orLater()) return
    when: "client creates a vhost with metadata"
    def vhost = "vhost-with-metadata"
    client.deleteVhost(vhost)
    client.createVhost(vhost, true, "vhost description", "production", "application1", "realm1")
    def vhi = client.getVhost(vhost)

    then: "the vhost is created"
    vhi.name == vhost
    vhi.description == "vhost description"
    vhi.tags.size() == 3
    vhi.tags.contains("production") && vhi.tags.contains("application1") && vhi.tags.contains("realm1")
    vhi.tracing

    cleanup:
    if (isVersion38orLater()) {
      client.deleteVhost(vhost)
    }

  }

  
  def "DELETE /api/vhosts/{name} when vhost exists"() {
    given: "a vhost named hop-test-to-be-deleted"
    def s = "hop-test-to-be-deleted"
    client.createVhost(s)

    when: "the vhost is deleted"
    client.deleteVhost(s)

    then: "it no longer exists"
    client.getVhost(s) == null

  }

  
  def "DELETE /api/vhosts/{name} when vhost DOES NOT exist"() {
    given: "no vhost named hop-test-to-be-deleted"
    def s = "hop-test-to-be-deleted"
    client.deleteVhost(s)

    when: "the vhost is deleted"
    client.deleteVhost(s)

    then: "it is a no-op"
    client.getVhost(s) == null

  }

  
  def "GET /api/vhosts/{name}/permissions when vhost exists"() {
    when: "permissions for vhost / are listed"
    def s = "/"
    def xs = client.getPermissionsIn(s)

    then: "they include permissions for the guest user"
    UserPermissions x = xs.find { (it.user == "guest") }
    x.read == ".*"

  }

  
  def "GET /api/vhosts/{name}/permissions when vhost DOES NOT exist"() {
    when: "permissions for vhost trololowut are listed"
    def s = "trololowut"
    def xs = client.getPermissionsIn(s)

    then: "method returns null"
    xs == null

  }

  
  def "GET /api/vhosts/{name}/topic-permissions when vhost exists"() {
    if (!isVersion37orLater()) return
    when: "topic-permissions for vhost / are listed"
    def s = "/"
    def xs = client.getTopicPermissionsIn(s)

    then: "they include topic permissions for the guest user"
    TopicPermissions x = xs.find { (it.user == "guest") }
    x.exchange == "amq.topic"
    x.read == ".*"

  }

  
  def "GET /api/vhosts/{name}/topic-permissions when vhost DOES NOT exist"() {
    if (!isVersion37orLater()) return
    when: "topic permissions for vhost trololowut are listed"
    def s = "trololowut"
    def xs = client.getTopicPermissionsIn(s)

    then: "method returns null"
    xs == null

  }

  
  def "GET /api/users"() {
    when: "users are listed"
    def xs = client.getUsers()
    def version = client.getOverview().getServerVersion()

    then: "a list of users is returned"
    def x = xs.find { (it.name == "guest") }
    x.name == "guest"
    x.passwordHash != null
    if (isVersion36orLater(version)) {
      x.hashingAlgorithm != null
    }
    x.tags.contains("administrator")

  }

  
  def "GET /api/users/{name} when user exists"() {
    when: "user guest if fetched"
    def x = client.getUser("guest")
    def version = client.getOverview().getServerVersion()

    then: "user info returned"
    x.name == "guest"
    x.passwordHash != null
    if (isVersion36orLater(version)) {
      x.hashingAlgorithm != null
    }
    x.tags.contains("administrator")

  }

  
  def "GET /api/users/{name} when user DOES NOT exist"() {
    when: "user lolwut if fetched"
    def x = client.getUser("lolwut")

    then: "null is returned"
    x == null

  }

  
  def "PUT /api/users/{name} updates user tags"() {
    given: "user alt-user"
    def u = "alt-user"
    client.deleteUser(u)
    client.createUser(u, u.toCharArray(), Arrays.asList("original", "management"))
    awaitEventPropagation()

    when: "alt-user's tags are updated"
    client.updateUser(u, u.toCharArray(), Arrays.asList("management", "updated"))
    awaitEventPropagation()

    and: "alt-user info is reloaded"
    def x = client.getUser(u)

    then: "alt-user has new tags"
    x.tags.contains("updated")
    !x.tags.contains("original")

  }

  
  def "DELETE /api/users/{name}"() {
    given: "user alt-user"
    def u = "alt-user"
    client.deleteUser(u)
    client.createUser(u, u.toCharArray(), Arrays.asList("original", "management"))
    awaitEventPropagation()

    when: "alt-user is deleted"
    client.deleteUser(u)
    awaitEventPropagation()

    and: "alt-user info is reloaded"
    def x = client.getUser(u)

    then: "deleted user is gone"
    x == null

  }

  
  def "GET /api/users/{name}/permissions when user exists"() {
    when: "permissions for user guest are listed"
    def s = "guest"
    def xs = client.getPermissionsOf(s)

    then: "they include permissions for the / vhost"
    UserPermissions x = xs.find { (it.vhost == "/") }
    x.read == ".*"

  }

  
  def "GET /api/users/{name}/permissions when user DOES NOT exist"() {
    when: "permissions for user trololowut are listed"
    def s = "trololowut"
    def xs = client.getPermissionsOf(s)

    then: "method returns null"
    xs == null

  }

  
  def "GET /api/users/{name}/topic-permissions when user exists"() {
    if (!isVersion37orLater()) return
    when: "topic permissions for user guest are listed"
    def s = "guest"
    def xs = client.getTopicPermissionsOf(s)

    then: "they include topic permissions for the / vhost"
    TopicPermissions x = xs.find { (it.vhost == "/") }
    x.exchange == "amq.topic"
    x.read == ".*"

  }

  
  def "GET /api/users/{name}/topic-permissions when user DOES NOT exist"() {
    if (!isVersion37orLater()) return
    when: "topic permissions for user trololowut are listed"
    def s = "trololowut"
    def xs = client.getTopicPermissionsOf(s)

    then: "method returns null"
    xs == null

  }

  
  def "PUT /api/users/{name} with a blank password hash"() {
    given: "user alt-user with a blank password hash"
    def u = "alt-user"
    // blank password hash means only authentication using alternative
    // authentication mechanisms such as x509 certificates is possible. MK.
    def h = ""
    client.deleteUser(u)
    client.createUserWithPasswordHash(u, h.toCharArray(), Arrays.asList("original", "management"))
    client.updatePermissions("/", u, new UserPermissions(".*", ".*", ".*"))

    when: "alt-user tries to connect with a blank password"
    openConnection("alt-user", "alt-user")

    then: "connection is refused"
    // it would have a chance of being accepted if the x509 authentication mechanism was used. MK.
    Exception e = thrown()
    e instanceof AuthenticationFailureException || e instanceof IOException

    cleanup:
    client.deleteUser(u)

  }

  
  def "GET /api/whoami"() {
    when: "client retrieves active name authentication details"
    def res = client.whoAmI()

    then: "the details are returned"
    res.name == DEFAULT_USERNAME
    res.tags.contains("administrator")

  }

  
  def "GET /api/permissions"() {
    when: "all permissions are listed"
    def s = "guest"
    def xs = client.getPermissions()

    then: "they include permissions for user guest in vhost /"
    def UserPermissions x = xs.find { it.vhost == "/" && it.user == s }
    x.read == ".*"

  }

  
  def "GET /api/permissions/{vhost}/:user when both vhost and user exist"() {
    when: "permissions of user guest in vhost / are listed"
    def u = "guest"
    def v = "/"
    def UserPermissions x = client.getPermissions(v, u)

    then: "a single permissions object is returned"
    x.read == ".*"

  }

  
  def "GET /api/permissions/{vhost}/:user when vhost DOES NOT exist"() {
    when: "permissions of user guest in vhost lolwut are listed"
    def u = "guest"
    def v = "lolwut"
    def UserPermissions x = client.getPermissions(v, u)

    then: "null is returned"
    x == null

  }

  
  def "GET /api/permissions/{vhost}/:user when username DOES NOT exist"() {
    when: "permissions of user lolwut in vhost / are lispermted"
    def u = "lolwut"
    def v = "/"
    def UserPermissions x = client.getPermissions(v, u)

    then: "null is returned"
    x == null

  }

  
  def "PUT /api/permissions/{vhost}/:user when both user and vhost exist"() {
    given: "vhost hop-vhost1 exists"
    def v = "hop-vhost1"
    client.createVhost(v)
    and: "user hop-user1 exists"
    def u = "hop-user1"
    client.createUser(u, "test".toCharArray(), Arrays.asList("management", "http", "policymaker"))

    when: "permissions of user guest in vhost / are updated"
    client.updatePermissions(v, u, new UserPermissions("read", "write", "configure"))

    and: "permissions are reloaded"
    def UserPermissions x = client.getPermissions(v, u)

    then: "a single permissions object is returned"
    x.read == "read"
    x.write == "write"
    x.configure == "configure"

    cleanup:
    client.deleteVhost(v)
    client.deleteUser(u)

  }

  
  def "PUT /api/permissions/{vhost}/:user when vhost DOES NOT exist"() {
    given: "vhost hop-vhost1 DOES NOT exist"
    def v = "hop-vhost1"
    client.deleteVhost(v)
    and: "user hop-user1 exists"
    def u = "hop-user1"
    client.createUser(u, "test".toCharArray(), Arrays.asList("management", "http", "policymaker"))

    when: "permissions of user guest in vhost / are updated"
    client.updatePermissions(v, u, new UserPermissions("read", "write", "configure"))

    then: "an exception is thrown"
    def e = thrown(Exception)
    exceptionStatus(e) == 400

    cleanup:
    client.deleteUser(u)

  }

  
  def "DELETE /api/permissions/{vhost}/:user when both vhost and username exist"() {
    given: "vhost hop-vhost1 exists"
    def v = "hop-vhost1"
    client.createVhost(v)
    and: "user hop-user1 exists"
    def u = "hop-user1"
    client.createUser(u, "test".toCharArray(), Arrays.asList("management", "http", "policymaker"))

    and: "permissions of user guest in vhost / are set"
    client.updatePermissions(v, u, new UserPermissions("read", "write", "configure"))
    def UserPermissions x = client.getPermissions(v, u)
    x.read == "read"

    when: "permissions are cleared"
    client.clearPermissions(v, u)

    then: "no permissions are returned on reload"
    def UserPermissions y = client.getPermissions(v, u)
    y == null

    cleanup:
    client.deleteVhost(v)
    client.deleteUser(u)

  }

  
  def "GET /api/topic-permissions"() {
    if (!isVersion37orLater()) return
    when: "all topic permissions are listed"
    def s = "guest"
    def xs = client.getTopicPermissions()

    then: "they include topic permissions for user guest in vhost /"
    def TopicPermissions x = xs.find { it.vhost == "/" && it.user == s }
    x.exchange == "amq.topic"
    x.read == ".*"

  }

  
  def "GET /api/topic-permissions/{vhost}/:user when both vhost and user exist"() {
    if (!isVersion37orLater()) return
    when: "topic permissions of user guest in vhost / are listed"
    def u = "guest"
    def v = "/"
    def xs = client.getTopicPermissions(v, u)

    then: "a list of topic permissions objects is returned"
    def TopicPermissions x = xs.find { it.vhost == v && it.user == u }
    x.exchange == "amq.topic"
    x.read == ".*"

  }

  
  def "GET /api/topic-permissions/{vhost}/:user when vhost DOES NOT exist"() {
    if (!isVersion37orLater()) return
    when: "topic permissions of user guest in vhost lolwut are listed"
    def u = "guest"
    def v = "lolwut"
    def xs = client.getTopicPermissions(v, u)

    then: "null is returned"
    xs == null

  }

  
  def "GET /api/topic-permissions/{vhost}/:user when username DOES NOT exist"() {
    if (!isVersion37orLater()) return
    when: "topic permissions of user lolwut in vhost / are listed"
    def u = "lolwut"
    def v = "/"
    def xs = client.getTopicPermissions(v, u)

    then: "null is returned"
    xs == null

  }

  
  def "PUT /api/topic-permissions/{vhost}/:user when both user and vhost exist"() {
    given: "vhost hop-vhost1 exists"
    def v = "hop-vhost1"
    client.createVhost(v)
    and: "user hop-user1 exists"
    def u = "hop-user1"
    client.createUser(u, "test".toCharArray(), Arrays.asList("management", "http", "policymaker"))

    if (!isVersion37orLater()) return

    when: "topic permissions of user guest in vhost / are updated"
    client.updateTopicPermissions(v, u, new TopicPermissions("amq.topic", "read", "write"))

    and: "topic permissions are reloaded"
    def xs = client.getTopicPermissions(v, u)

    then: "a list with a single topiic permissions object is returned"
    xs.size() == 1
    xs.get(0).exchange == "amq.topic"
    xs.get(0).read == "read"
    xs.get(0).write == "write"

    cleanup:
    client.deleteVhost(v)
    client.deleteUser(u)

  }

  
  def "PUT /api/topic-permissions/{vhost}/:user when vhost DOES NOT exist"() {
    given: "vhost hop-vhost1 DOES NOT exist"
    def v = "hop-vhost1"
    client.deleteVhost(v)
    and: "user hop-user1 exists"
    def u = "hop-user1"
    client.createUser(u, "test".toCharArray(), Arrays.asList("management", "http", "policymaker"))

    if (!isVersion37orLater()) return

    when: "topic permissions of user guest in vhost / are updated"
    client.updateTopicPermissions(v, u, new TopicPermissions("amq.topic", "read", "write"))

    then: "an exception is thrown"
    def e = thrown(Exception.class)
    exceptionStatus(e) == 400

    cleanup:
    client.deleteUser(u)

  }

  
  def "DELETE /api/topic-permissions/{vhost}/:user when both vhost and username exist"() {
    given: "vhost hop-vhost1 exists"
    def v = "hop-vhost1"
    client.createVhost(v)
    and: "user hop-user1 exists"
    def u = "hop-user1"
    client.createUser(u, "test".toCharArray(), Arrays.asList("management", "http", "policymaker"))

    if (!isVersion37orLater()) return

    and: "topic permissions of user guest in vhost / are set"
    client.updateTopicPermissions(v, u, new TopicPermissions("amq.topic", "read", "write"))
    def xs = client.getTopicPermissions(v, u)
    xs.size() == 1

    when: "topic permissions are cleared"
    client.clearTopicPermissions(v, u)

    then: "no topic permissions are returned on reload"
    def ys = client.getTopicPermissions(v, u)
    ys == null

    cleanup:
    client.deleteVhost(v)
    client.deleteUser(u)

  }

//  def "GET /api/parameters"() {
    // TODO
//  }

  
  def "GET /api/policies"() {
    given: "at least one policy was declared"
    def v = "/"
    def s = "hop.test"
    def d = new HashMap<String, Object>()
    def p = ".*"
    d.put("ha-mode", "all")
    client.declarePolicy(v, s, new PolicyInfo(p, 0, null, d))

    when: "client lists policies"
    PolicyInfo[] xs = awaitEventPropagation({ client.getPolicies() }) as PolicyInfo[]

    then: "a list of policies is returned"
    def x = xs.first()
    verifyPolicyInfo(x)

    cleanup:
    client.deletePolicy(v, s)

  }

  
  def "GET /api/policies/{vhost} when vhost exists"() {
    given: "at least one policy was declared in vhost /"
    def v = "/"
    def s = "hop.test"
    def d = new HashMap<String, Object>()
    def p = ".*"
    d.put("ha-mode", "all")
    client.declarePolicy(v, s, new PolicyInfo(p, 0, null, d))

    when: "client lists policies"
    PolicyInfo[] xs = awaitEventPropagation({ client.getPolicies("/") }) as PolicyInfo[]

    then: "a list of queues is returned"
    def x = xs.first()
    verifyPolicyInfo(x)

    cleanup:
    client.deletePolicy(v, s)

  }

  
  def "GET /api/policies/{vhost} when vhost DOES NOT exists"() {
    given: "vhost lolwut DOES not exist"
    def v = "lolwut"
    client.deleteVhost(v)

    when: "client lists policies"
    def xs = awaitEventPropagation({ client.getPolicies(v) })

    then: "null is returned"
    xs == null

  }

  def "GET /api/operator-policies"() {
    given: "at least one operator policy was declared"
    def v = "/"
    def s = "hop.test"
    def d = new HashMap<String, Object>()
    def p = ".*"
    d.put("max-length", 6)
    client.declareOperatorPolicy(v, s, new PolicyInfo(p, 0, null, d))

    when: "client lists policies"
    PolicyInfo[] xs = awaitEventPropagation({ client.getOperatorPolicies() }) as PolicyInfo[]

    then: "a list of policies is returned"
    def x = xs.first()
    verifyPolicyInfo(x)

    cleanup:
    client.deleteOperatorPolicy(v, s)

  }


  def "GET /api/operator-policies/{vhost} when vhost exists"() {
    given: "at least one operator  was declared in vhost /"
    def v = "/"
    def s = "hop.test"
    def d = new HashMap<String, Object>()
    def p = ".*"
    d.put("max-length", 6)
    client.declareOperatorPolicy(v, s, new PolicyInfo(p, 0, null, d))

    when: "client lists policies"
    PolicyInfo[] xs = awaitEventPropagation({ client.getOperatorPolicies("/") }) as PolicyInfo[]

    then: "a list of queues is returned"
    def x = xs.first()
    verifyPolicyInfo(x)

    cleanup:
    client.deleteOperatorPolicy(v, s)

  }


  def "GET /api/operator-policies/{vhost} when vhost DOES NOT exists"() {
    given: "vhost lolwut DOES not exist"
    def v = "lolwut"
    client.deleteVhost(v)

    when: "client lists operator policies"
    def xs = awaitEventPropagation({ client.getOperatorPolicies(v) })

    then: "null is returned"
    xs == null

  }

  
  def "GET /api/aliveness-test/{vhost}"() {
    when: "client performs aliveness check for the / vhost"
    def hasSucceeded = client.alivenessTest("/")

    then: "the check succeeds"
    hasSucceeded

  }

  
  def "GET /api/cluster-name"() {
    when: "client fetches cluster name"
    def ClusterId s = client.getClusterName()

    then: "cluster name is returned"
    s.getName() != null

  }

  
  def "PUT /api/cluster-name"() {
    given: "cluster name"
    def String s = client.getClusterName().name

    when: "cluster name is set to rabbit@warren"
    client.setClusterName("rabbit@warren")

    and: "cluster name is reloaded"
    def String x = client.getClusterName().name

    then: "the name is updated"
    x == "rabbit@warren"

    cleanup:
    client.setClusterName(s)

  }

  
  def "GET /api/extensions"() {
    given: "a node with the management plugin enabled"
    when: "client requests a list of (plugin) extensions"
    List<Map<String, Object>> xs = client.getExtensions()

    then: "a list of extensions is returned"
    !xs.isEmpty()

  }

  
  def "GET /api/definitions (version, vhosts, permissions, topic permissions)"() {
    when: "client requests the definitions"
    Definitions d = client.getDefinitions()

    then: "broker definitions are returned"
    d.getServerVersion() != null
    !d.getServerVersion().trim().isEmpty()
    !d.getVhosts().isEmpty()
    !d.getVhosts().get(0).getName().isEmpty()
    !d.getVhosts().isEmpty()
    d.getVhosts().get(0).getName() != null
    !d.getVhosts().get(0).getName().isEmpty()
    !d.getPermissions().isEmpty()
    d.getPermissions().get(0).getUser() != null
    !d.getPermissions().get(0).getUser().isEmpty()
    if (isVersion37orLater()) {
      d.getTopicPermissions().get(0).getUser() != null
      !d.getTopicPermissions().get(0).getUser().isEmpty()
    }

  }

  
  def "GET /api/definitions (users)"() {
    when: "client requests the definitions"
    Definitions d = client.getDefinitions()

    then: "user definitions are returned"
    !d.getUsers().isEmpty()
    d.getUsers().get(0).getName() != null
    !d.getUsers().get(0).getName().isEmpty()

  }

  
  def "GET /api/definitions (queues)"() {
    given: "a basic topology"
    client.declareQueue("/","queue1",new QueueInfo(false,false,false))
    client.declareQueue("/","queue2",new QueueInfo(false,false,false))
    client.declareQueue("/","queue3",new QueueInfo(false,false,false))
    when: "client requests the definitions"
    Definitions d = client.getDefinitions()

    then: "broker definitions are returned"
    !d.getQueues().isEmpty()
    d.getQueues().size() >= 3
    QueueInfo q = d.getQueues().find { it.name == "queue1" && it.vhost == "/" }
    q != null
    q.vhost == "/"
    q.name == "queue1"
    !q.durable
    !q.exclusive
    !q.autoDelete

    cleanup:
    client.deleteQueue("/","queue1")
    client.deleteQueue("/","queue2")
    client.deleteQueue("/","queue3")

  }

  
  def "GET /api/definitions (exchanges)"() {
    given: "a basic topology"
    client.declareExchange("/", "exchange1", new ExchangeInfo("fanout", false, false))
    client.declareExchange("/", "exchange2", new ExchangeInfo("direct", false, false))
    client.declareExchange("/", "exchange3", new ExchangeInfo("topic", false, false))
    when: "client requests the definitions"
    Definitions d = client.getDefinitions()

    then: "broker definitions are returned"
    !d.getExchanges().isEmpty()
    d.getExchanges().size() >= 3
    ExchangeInfo e = d.getExchanges().find { (it.name == "exchange1") }
    e != null
    e.vhost == "/"
    e.name == "exchange1"
    !e.durable
    !e.internal
    !e.autoDelete

    cleanup:
    client.deleteExchange("/","exchange1")
    client.deleteExchange("/","exchange2")
    client.deleteExchange("/","exchange3")

  }

  
  def "GET /api/definitions (bindings)"() {
    given: "a basic topology"
    client.declareQueue("/", "queue1", new QueueInfo(false, false, false))
    client.bindQueue("/", "queue1", "amq.fanout", "")
    when: "client requests the definitions"
    Definitions d = client.getDefinitions()

    then: "broker definitions are returned"
    !d.getBindings().isEmpty()
    d.getBindings().size() >= 1
    BindingInfo b = d.getBindings().find {
      it.source == "amq.fanout" && it.destination == "queue1" && it.destinationType == QUEUE
    }
    b != null
    b.vhost == "/"
    b.source == "amq.fanout"
    b.destination == "queue1"
    b.destinationType == QUEUE

    cleanup:
    client.deleteQueue("/","queue1")

  }

  def "GET /api/definitions (parameters)"() {
    given: "a (shovel) parameter defined"
    ShovelDetails value = new ShovelDetails("amqp://localhost:5672/vh1", "amqp://localhost:5672/vh2", 30, true, null)
    value.setSourceQueue("queue1")
    value.setDestinationExchange("exchange1")
    value.setSourcePrefetchCount(50L)
    value.setSourceDeleteAfter("never")
    value.setDestinationAddTimestampHeader(true)
    client.declareShovel("/", new ShovelInfo("shovel1", value))
    when: "client gets the parameters and global parameters"
    Definitions d = client.getDefinitions()
    List<RuntimeParameter<Object>> parameters = d.getParameters()
    List<RuntimeParameter<Object>> globalParameters = d.getGlobalParameters()

    then: "a parameter with a JSON document value and a parameter with a string value are deserialized"
    !parameters.isEmpty()
    RuntimeParameter<Object> parameter = parameters.find { (it.name == "shovel1") } as RuntimeParameter<Object>
    parameter != null
    parameter.name == "shovel1"
    parameter.vhost == "/"
    parameter.component == "shovel"
    parameter.value instanceof Map
    parameter.value["src-queue"] == "queue1"
    parameter.value["dest-exchange"] == "exchange1"

    !globalParameters.isEmpty()
    RuntimeParameter<Object> globalParameter = globalParameters.find { (it.name == "internal_cluster_id") } as RuntimeParameter<Object>
    globalParameter != null
    globalParameter.name == "internal_cluster_id"
    globalParameter.value instanceof String

    cleanup:
    client.deleteShovel("/","shovel1")
    client.deleteQueue("/", "queue1")

  }
  
  def "GET /api/parameters/shovel"() {
    given: "a shovel defined"
    ShovelDetails value = new ShovelDetails("amqp://localhost:5672/vh1", "amqp://localhost:5672/vh2", 30, true, null)
    value.setSourceQueue("queue1")
    value.setDestinationExchange("exchange1")
    value.setSourcePrefetchCount(50L)
    value.setSourceDeleteAfter("never")
    value.setDestinationAddTimestampHeader(true)
    client.declareShovel("/", new ShovelInfo("shovel1", value))
    when: "client requests the shovels"
    def shovels = awaitEventPropagation { client.getShovels() }

    then: "shovel definitions are returned"
    !shovels.isEmpty()
    shovels.size() >= 1
    ShovelInfo s = shovels.find { (it.name == "shovel1") } as ShovelInfo
    s != null
    s.name == "shovel1"
    s.virtualHost == "/"
    s.details.sourceURIs.equals(["amqp://localhost:5672/vh1"])
    s.details.sourceExchange == null
    s.details.sourceQueue == "queue1"
    s.details.destinationURIs.equals(["amqp://localhost:5672/vh2"])
    s.details.destinationExchange == "exchange1"
    s.details.destinationQueue == null
    s.details.reconnectDelay == 30
    s.details.addForwardHeaders
    s.details.publishProperties == null
    s.details.sourcePrefetchCount == 50L
    s.details.sourceDeleteAfter == "never"
    s.details.destinationAddTimestampHeader

    cleanup:
    client.deleteShovel("/","shovel1")
    client.deleteQueue("/", "queue1")

  }

  
  def "GET /api/parameters/shovel with multiple URIs"() {
    given: "a shovel defined with multiple URIs"
    ShovelDetails value = new ShovelDetails(["amqp://localhost:5672/vh1", "amqp://localhost:5672/vh3"], ["amqp://localhost:5672/vh2", "amqp://localhost:5672/vh4"], 30, true, null)
    value.setSourceQueue("queue1")
    value.setDestinationExchange("exchange1")
    value.setSourcePrefetchCount(50L)
    value.setSourceDeleteAfter("never")
    value.setDestinationAddTimestampHeader(true)
    client.declareShovel("/", new ShovelInfo("shovel2", value))
    when: "client requests the shovels"
    def shovels = awaitEventPropagation { client.getShovels() }

    then: "shovel definitions are returned"
    !shovels.isEmpty()
    shovels.size() >= 1
    ShovelInfo s = shovels.find { (it.name == "shovel2") } as ShovelInfo
    s != null
    s.name == "shovel2"
    s.virtualHost == "/"
    s.details.sourceURIs.equals(["amqp://localhost:5672/vh1", "amqp://localhost:5672/vh3"])
    s.details.sourceExchange == null
    s.details.sourceQueue == "queue1"
    s.details.destinationURIs.equals(["amqp://localhost:5672/vh2", "amqp://localhost:5672/vh4"])
    s.details.destinationExchange == "exchange1"
    s.details.destinationQueue == null
    s.details.reconnectDelay == 30
    s.details.addForwardHeaders
    s.details.publishProperties == null
    s.details.sourcePrefetchCount == 50L
    s.details.sourceDeleteAfter == "never"
    s.details.destinationAddTimestampHeader

    cleanup:
    client.deleteShovel("/","shovel2")
    client.deleteQueue("/", "queue1")

  }

  
  def "PUT /api/parameters/shovel with an empty publish properties map"() {
    given: "a Shovel with empty publish properties"
    ShovelDetails value = new ShovelDetails("amqp://localhost:5672/vh1", "amqp://localhost:5672/vh2", 30, true, [:])
    value.setSourceQueue("queue1")
    value.setDestinationExchange("exchange1")

    when: "client tries to declare a Shovel"
    client.declareShovel("/", new ShovelInfo("shovel10", value))

    then: "an illegal argument exception is thrown"
    thrown(IllegalArgumentException)

    cleanup:
    client.deleteShovel("/","shovel1")
    client.deleteQueue("/", "queue1")

  }

  
  def "GET /api/shovels"() {
    given: "a basic topology"
    ShovelDetails value = new ShovelDetails("amqp://localhost:5672/vh1", "amqp://localhost:5672/vh2", 30, true, null)
    value.setSourceQueue("queue1")
    value.setDestinationExchange("exchange1")
    def shovelName = "shovel2"
    client.declareShovel("/", new ShovelInfo(shovelName, value))

    when: "client requests the shovels status"
    def shovels = awaitEventPropagation { client.getShovelsStatus() }

    then: "shovels status are returned"
    !shovels.isEmpty()
    shovels.size() >= 1
    ShovelStatus s = shovels.find { (it.name == shovelName) } as ShovelStatus
    s != null
    s.name == shovelName
    s.virtualHost == "/"
    s.type == "dynamic"
    waitAtMostUntilTrue(30, {
      ShovelStatus shovelStatus = client.getShovelsStatus().find { (it.name == shovelName) } as ShovelStatus
      shovelStatus.state == "running"
    })
    ShovelStatus status = client.getShovelsStatus().find { (it.name == shovelName) } as ShovelStatus
    status.state == "running"
    status.sourceURI == "amqp://localhost:5672/vh1"
    status.destinationURI == "amqp://localhost:5672/vh2"

    cleanup:
    client.deleteShovel("/", shovelName)

  }

  
  def "GET /api/parameters/federation-upstream declare and get at root vhost with non-null ack mode"() {
    given: "an upstream with non-null ack mode"
    def vhost = "/"
    def upstreamName = "upstream1"
    UpstreamDetails upstreamDetails = new UpstreamDetails()
    upstreamDetails.setUri("amqp://localhost:5672")
    upstreamDetails.setAckMode(AckMode.ON_CONFIRM)
    client.declareUpstream(vhost, upstreamName, upstreamDetails)

    when: "client requests the upstreams"
    def upstreams = awaitEventPropagation { client.getUpstreams() }

    then: "list of upstreams that contains the new upstream is returned and ack mode is correctly retrieved"
    verifyUpstreamDefinitions(vhost, upstreams, upstreamName)
    UpstreamInfo upstream = upstreams.find { (it.name == upstreamName) } as UpstreamInfo
    upstream.value.ackMode == AckMode.ON_CONFIRM

    cleanup:
    client.deleteUpstream(vhost, upstreamName)

  }

  
  def "GET /api/parameters/federation-upstream declare and get at root vhost"() {
    given: "an upstream"
    def vhost = "/"
    def upstreamName = "upstream1"
    declareUpstream(client, vhost, upstreamName)

    when: "client requests the upstreams"
    def upstreams = awaitEventPropagation { client.getUpstreams() }

    then: "list of upstreams that contains the new upstream is returned"
    verifyUpstreamDefinitions(vhost, upstreams, upstreamName)

    cleanup:
    client.deleteUpstream(vhost, upstreamName)

  }

  
  def "GET /api/parameters/federation-upstream declare and get at non-root vhost"() {
    given: "an upstream"
    def vhost = "foo"
    def upstreamName = "upstream2"
    client.createVhost(vhost)
    declareUpstream(client, vhost, upstreamName)

    when: "client requests the upstreams"
    def upstreams = awaitEventPropagation { client.getUpstreams(vhost) }

    then: "list of upstreams that contains the new upstream is returned"
    verifyUpstreamDefinitions(vhost, upstreams, upstreamName)

    cleanup:
    client.deleteUpstream(vhost,upstreamName)
    client.deleteVhost(vhost)

  }

  
  def "PUT /api/parameters/federation-upstream with null upstream uri"() {
    given: "an Upstream without upstream uri"
    UpstreamDetails upstreamDetails = new UpstreamDetails()

    when: "client tries to declare an Upstream"
    client.declareUpstream("/", "upstream3", upstreamDetails)

    then: "an illegal argument exception is thrown"
    thrown(IllegalArgumentException)

  }

  
  def "DELETE /api/parameters/federation-upstream/{vhost}/{name}"() {
    given: "upstream upstream4 in vhost /"
    def vhost = "/"
    def upstreamName = "upstream4"
    declareUpstream(client, vhost, upstreamName)

    List<UpstreamInfo> upstreams = awaitEventPropagation { client.getUpstreams() } as List<UpstreamInfo>
    verifyUpstreamDefinitions(vhost, upstreams, upstreamName)

    when: "client deletes upstream upstream4 in vhost /"
    client.deleteUpstream(vhost, upstreamName)

    and: "upstream list in / is reloaded"
    upstreams = client.getUpstreams()

    then: "upstream4 no longer exists"
    upstreams.find { (it.name == upstreamName) } == null

  }

  
  def "GET /api/parameters/federation-upstream-set declare and get"() {
    given: "an upstream set with two upstreams"
    def vhost = "/"
    def upstreamSetName = "upstream-set-1"
    def upstreamA = "A"
    def upstreamB = "B"
    def policyName = "federation-policy"
    declareUpstream(client, vhost, upstreamA)
    declareUpstream(client, vhost, upstreamB)
    def d1 = new UpstreamSetDetails()
    d1.setUpstream(upstreamA)
    d1.setExchange("exchangeA")
    def d2 = new UpstreamSetDetails()
    d2.setUpstream(upstreamB)
    d2.setExchange("exchangeB")
    def detailsSet = new ArrayList()
    detailsSet.add(d1)
    detailsSet.add(d2)
    client.declareUpstreamSet(vhost, upstreamSetName, detailsSet)
    PolicyInfo p = new PolicyInfo()
    p.setApplyTo("exchanges")
    p.setName(policyName)
    p.setPattern("amq\\.topic")
    p.setDefinition(Collections.singletonMap("federation-upstream-set", upstreamSetName))
    client.declarePolicy(vhost, policyName, p)

    when: "client requests the upstream set list"
    def upstreamSets = awaitEventPropagation { client.getUpstreamSets() }

    then: "upstream set with two upstreams is returned"
    !upstreamSets.isEmpty()
    UpstreamSetInfo upstreamSet = upstreamSets.find { (it.name == upstreamSetName) } as UpstreamSetInfo
    upstreamSet != null
    upstreamSet.name == upstreamSetName
    upstreamSet.vhost == vhost
    upstreamSet.component == "federation-upstream-set"
    List<UpstreamSetDetails> upstreams = upstreamSet.value
    upstreams != null
    upstreams.size() == 2
    UpstreamSetDetails responseUpstreamA = upstreams.find { (it.upstream == upstreamA) }
    responseUpstreamA != null
    responseUpstreamA.upstream == upstreamA
    responseUpstreamA.exchange == "exchangeA"
    UpstreamSetDetails responseUpstreamB = upstreams.find { (it.upstream == upstreamB) }
    responseUpstreamB != null
    responseUpstreamB.upstream == upstreamB
    responseUpstreamB.exchange == "exchangeB"

    cleanup:
    client.deletePolicy(vhost, policyName)
    client.deleteUpstreamSet(vhost,upstreamSetName)
    client.deleteUpstream(vhost,upstreamA)
    client.deleteUpstream(vhost,upstreamB)

  }

  
  def "PUT /api/parameters/federation-upstream-set without upstreams"() {
    given: "an Upstream without upstream uri"
    def upstreamSetDetails = new UpstreamSetDetails()
    def detailsSet = new ArrayList()
    detailsSet.add(upstreamSetDetails)

    when: "client tries to declare an Upstream"
    client.declareUpstreamSet("/", "upstrea-set-2", detailsSet)

    then: "an illegal argument exception is thrown"
    thrown(IllegalArgumentException)

  }

  
  def "GET /api/vhost-limits"() {
    given: "several virtual hosts with limits"
    def vhost1 = "virtual-host-with-limits-1"
    def vhost2 = "virtual-host-with-limits-2"
    client.createVhost(vhost1)
    client.createVhost(vhost2)
    client.limitMaxNumberOfQueues(vhost1, 100)
    client.limitMaxNumberOfConnections(vhost1, 10)
    client.limitMaxNumberOfQueues(vhost2, 200)
    client.limitMaxNumberOfConnections(vhost2, 20)
    client.limitMaxNumberOfQueues("/", 300)
    client.limitMaxNumberOfConnections("/", 30)

    when: "client tries to look up limits"
    def limits = client.getVhostLimits()

    then: "limits match the definitions"
    limits.size() == 3
    def limits1 = limits.find { it.vhost == vhost1}
    limits1.maxQueues == 100
    limits1.maxConnections == 10
    def limits2 = limits.find { it.vhost == vhost2}
    limits2.maxQueues == 200
    limits2.maxConnections == 20
    def limits3 = limits.find { it.vhost == "/"}
    limits3.maxQueues == 300
    limits3.maxConnections == 30

    cleanup:
    client.deleteVhost(vhost1)
    client.deleteVhost(vhost2)
    client.clearMaxQueuesLimit("/")
    client.clearMaxConnectionsLimit("/")

  }

  
  def "GET /api/vhost-limits without limits on any host"() {
    given: "the default configuration"

    when: "client tries to look up limits"
    def limits = client.getVhostLimits()

    then: "it should return one row for the default virtual host"
    limits.size() == 1
    def limits1 = limits.find { it.vhost == "/"}
    limits1.maxQueues == -1
    limits1.maxConnections == -1

  }

  def "GET /api/global-parameters/mqtt_port_to_vhost_mapping without mqtt vhost port mapping"() {
    given: "rabbitmq deployment without mqtt port mappings defined"
    client.deleteMqttPortToVhostMapping()

    when: "client tries to look up mqtt vhost port mappings"
    def mqttPorts = client.getMqttPortToVhostMapping()

    then: "mqtt vhost port mappings are null"
    mqttPorts == null
  }

  def "GET /api/global-parameters/mqtt_port_to_vhost_mapping with a sample mapping"() {
    given: "a mqtt mapping with 2 vhosts defined"
    def mqttInputMap = Map.of(2024, "vhost1", 2025, "vhost2")
    client.setMqttPortToVhostMapping(mqttInputMap)

    when: "client tries to get mqtt port mappings"
    def mqttInfo = client.getMqttPortToVhostMapping()
    def mqttReturnValues = mqttInfo.getValue()

    then: "a map with 2 mqtt ports and vhosts is returned"
    mqttReturnValues == mqttInputMap

    cleanup:
    client.deleteMqttPortToVhostMapping()
  }

  def "PUT /api/global-parameters/mqtt_port_to_vhost_mapping with a blank vhost value"(){
    given: "a mqtt mapping with blank vhost"
    def mqttInputMap = Map.of(2024, " ", 2025, "vhost2")

    when: "client tries to set mqtt port mappings"
    client.setMqttPortToVhostMapping(mqttInputMap)

    then: "an illegal argument exception is thrown"
    thrown(IllegalArgumentException)
  }

  
  def "GET /api/vhost-limits/{vhost}"() {
    given: "a virtual host with limits"
    def vhost = "virtual-host-with-limits"
    client.createVhost(vhost)
    client.limitMaxNumberOfQueues(vhost, 100)
    client.limitMaxNumberOfConnections(vhost, 10)

    when: "client tries to look up limits for this virtual host"
    def limits = client.getVhostLimits(vhost)

    then: "limits match the definitions"
    limits.maxQueues == 100
    limits.maxConnections == 10

    cleanup:
    client.deleteVhost(vhost)

  }

  
  def "GET /api/vhost-limits/{vhost} vhost with no limits"() {
    given: "a virtual host without limits"
    def vhost = "virtual-host-without-limits"
    client.createVhost(vhost)

    when: "client tries to look up limits for this virtual host"
    def limits = client.getVhostLimits(vhost)

    then: "limits are set to -1"
    limits.maxQueues == -1
    limits.maxConnections == -1

    cleanup:
    client.deleteVhost(vhost)

  }

  
  def "GET /api/vhost-limits/{vhost} with non-existing vhost"() {
    given: "a virtual host that does not exist"
    def vhost = "virtual-host-that-does-not-exist"

    when: "client tries to look up limits for this virtual host"
    def limits = client.getVhostLimits(vhost)

    then: "limits are null"
    limits == null

    cleanup:
    client.deleteVhost(vhost)

  }

  
  def "DELETE /api/vhost-limits/{vhost}/max-queues"() {
    given: "a virtual host with max queues limit"
    def vhost = "virtual-host-max-queues-limit"
    client.createVhost(vhost)
    client.limitMaxNumberOfQueues(vhost, 42)

    when: "client clears the limit"
    client.clearMaxQueuesLimit(vhost)

    then: "limit is then looked up with value -1"
    client.getVhostLimits(vhost).maxQueues == -1
    client.getVhostLimits(vhost).maxConnections == -1

    cleanup:
    client.deleteVhost(vhost)

  }

  
  def "DELETE /api/vhost-limits/{vhost}/max-connections"() {
    given: "a virtual host with max connections limit"
    def vhost = "virtual-host-max-connections-limit"
    client.createVhost(vhost)
    client.limitMaxNumberOfConnections(vhost, 42)

    when: "client clears the limit"
    client.clearMaxConnectionsLimit(vhost)

    then: "limit is then looked up with value -1"
    client.getVhostLimits(vhost).maxConnections == -1
    client.getVhostLimits(vhost).maxQueues == -1

    cleanup:
    client.deleteVhost(vhost)

  }

  
  def "DELETE /api/vhost-limits/{vhost} with only one limit"() {
    given: "a virtual host with max queues and connections limits"
    def vhost = "virtual-host-max-queues-connections-limits"
    client.createVhost(vhost)
    client.limitMaxNumberOfQueues(vhost, 314)
    client.limitMaxNumberOfConnections(vhost, 42)

    when: "client clears one of the limits"
    client.clearMaxQueuesLimit(vhost)

    then: "the cleared limit is then returned as -1"
    client.getVhostLimits(vhost).maxQueues == -1

    then: "the other limit is left as-is"
    client.getVhostLimits(vhost).maxConnections == 42

    cleanup:
    client.deleteVhost(vhost)

  }

  protected static boolean awaitOn(CountDownLatch latch) {
    latch.await(10, TimeUnit.SECONDS)
  }

  protected static void verifyConnectionInfo(ConnectionInfo info) {
    assert info.port == ConnectionFactory.DEFAULT_AMQP_PORT
    assert !info.usesTLS
  }

  protected static void verifyUserConnectionInfo(UserConnectionInfo info, String username) {
    assert info.username == username
  }

  protected static void verifyChannelInfo(ChannelInfo chi, Channel ch) {
    assert chi.getConsumerCount() == 0
    assert chi.number == ch.getChannelNumber()
    assert chi.node.startsWith("rabbit@")
    assert chi.state == "running" ||
              chi.state == null // HTTP API may not be refreshed yet
    assert !chi.usesPublisherConfirms()
    assert !chi.transactional
  }

  protected static void verifyVhost(VhostInfo vhi, String version) {
    assert vhi.name == "/"
    assert !vhi.tracing
    if (isVersion37orLater(version)) {
      assert vhi.clusterState != null
    }
    if (isVersion38orLater(version)) {
      assert vhi.description != null
    }
  }

  protected Connection openConnection() {
    this.cf.newConnection()
  }

  @SuppressWarnings("GrMethodMayBeStatic")
  protected Connection openConnection(String username, String password) {
    def cf = new ConnectionFactory()
    cf.setUsername(username)
    cf.setPassword(password)
    cf.newConnection()
  }

  protected Connection openConnection(String clientProvidedName) {
    this.cf.newConnection(clientProvidedName)
  }

  protected static void verifyNode(NodeInfo node) {
    assert node != null
    assert node.name != null
    assert node.socketsUsed <= node.socketsTotal
    assert node.erlangProcessesUsed <= node.erlangProcessesTotal
    assert node.erlangRunQueueLength >= 0
    assert node.memoryUsed <= node.memoryLimit
  }

  @SuppressWarnings("GrEqualsBetweenInconvertibleTypes")
  protected static void verifyExchangeInfo(ExchangeInfo x) {
    assert x.type != null
    assert x.durable != null
    assert x.name != null
    assert x.autoDelete != null
  }

  protected static void verifyPolicyInfo(PolicyInfo x) {
    assert x.name != null
    assert x.vhost != null
    assert x.pattern != null
    assert x.definition != null
    assert x.applyTo != null
  }

  @SuppressWarnings("GrEqualsBetweenInconvertibleTypes")
  protected static void verifyQueueInfo(QueueInfo x) {
    assert x.name != null
    assert x.durable != null
    assert x.exclusive != null
    assert x.autoDelete != null
  }

  private static boolean checkVersionOrLater(String currentVersion, String expectedVersion) {
    String v = currentVersion.replaceAll("\\+.*\$", "")
    try {
      v == "0.0.0" ? true : compareVersions(v, expectedVersion) >= 0
    } catch (Exception e) {
      false
    }
  }

  static boolean isVersion36orLater(String currentVersion) {
    checkVersionOrLater(currentVersion, "3.6.0")
  }

  static boolean isVersion37orLater(String currentVersion) {
    checkVersionOrLater(currentVersion, "3.7.0")
  }

  static boolean isVersion38orLater(String currentVersion) {
    checkVersionOrLater(currentVersion, "3.8.0")
  }

  static boolean isVersion310orLater(String currentVersion) {
    checkVersionOrLater(currentVersion, "3.10.0")
  }

  boolean isVersion37orLater() {
    return isVersion37orLater(brokerVersion)
  }

  boolean isVersion38orLater() {
    return isVersion38orLater(brokerVersion)
  }

  boolean isVersion310orLater() {
    return isVersion310orLater(brokerVersion)
  }

  /**
   * https://stackoverflow.com/questions/6701948/efficient-way-to-compare-version-strings-in-java
   *
   */
  static Integer compareVersions(String str1, String str2) {
    String[] vals1 = str1.split("\\.")
    String[] vals2 = str2.split("\\.")
    int i = 0
    // set index to first non-equal ordinal or length of shortest version string
    while (i < vals1.length && i < vals2.length && vals1[i] == vals2[i]) {
      i++
    }
    // compare first non-equal ordinal number
    if (i < vals1.length && i < vals2.length) {
      if(vals1[i].indexOf('-') != -1) {
        vals1[i] = vals1[i].substring(0,vals1[i].indexOf('-'))
      }
      if(vals2[i].indexOf('-') != -1) {
        vals2[i] = vals2[i].substring(0,vals2[i].indexOf('-'))
      }
      int diff = Integer.valueOf(vals1[i]) <=> Integer.valueOf(vals2[i])
      return Integer.signum(diff)
    }
    // the strings are equal or one string is a substring of the other
    // e.g. "1.2.3" = "1.2.3" or "1.2.3" < "1.2.3.4"
    else {
      return Integer.signum(vals1.length - vals2.length)
    }
  }

  /**
   * Statistics tables in the server are updated asynchronously,
   * in particular starting with rabbitmq/rabbitmq-management#236,
   * so in some cases we need to wait before GET'ing e.g. a newly opened connection.
   */
  protected static Object awaitEventPropagation(Closure callback) {
    if (callback) {
      int n = 0
      def result = callback()
      while (result?.isEmpty() && n < 10000) {
        Thread.sleep(100)
        n += 100
        result = callback()
      }
      assert n < 10000
      result
    }
    else {
      Thread.sleep(1000)
      null
    }
  }

  protected static boolean waitAtMostUntilTrue(int timeoutInSeconds, Closure<Boolean> callback) {
    if (callback()) {
      return true
    }
    int timeout = timeoutInSeconds * 1000
    int waited = 0
    while (waited <= timeout) {
      Thread.sleep(100)
      waited += 100
      if (callback()) {
        return true
      }
    }
    false
  }

  protected static Object awaitAllConnectionsClosed(client) {
      int n = 0
      def result = client.getConnections()
      while (result?.size() > 0 && n < 10000) {
        Thread.sleep(100)
        n += 100
        result = client.getConnections()
      }
      result
  }

  protected static void declareUpstream(client, vhost, upstreamName) {
    UpstreamDetails upstreamDetails = new UpstreamDetails()
    upstreamDetails.setUri("amqp://localhost:5672")
    client.declareUpstream(vhost, upstreamName, upstreamDetails)
  }

  protected static void verifyUpstreamDefinitions(vhost, upstreams, upstreamName) {
    assert !upstreams.isEmpty()
    UpstreamInfo upstream = upstreams.find { (it.name == upstreamName) } as UpstreamInfo
    assert upstream != null
    assert upstream.name == upstreamName
    assert upstream.vhost == vhost
    assert upstream.component == "federation-upstream"
    assert upstream.value.uri == "amqp://localhost:5672"
  }

  static int exceptionStatus(Exception e) {
    if (e instanceof HttpClientException){
      return ((HttpClientException) e).status();
    } else {
      throw new IllegalArgumentException("Unknown exception type: " + e.getClass());
    }
  }

}
