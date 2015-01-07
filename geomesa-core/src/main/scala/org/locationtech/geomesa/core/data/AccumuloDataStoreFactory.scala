/*
 * Copyright 2014 Commonwealth Computer Research, Inc.
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


package org.locationtech.geomesa.core.data

import java.io.Serializable
import java.util.{Map => JMap}
import javax.imageio.spi.ServiceRegistry

import org.apache.accumulo.core.client.mock.{MockConnector, MockInstance}
import org.apache.accumulo.core.client.security.tokens.{AuthenticationToken, PasswordToken}
import org.apache.accumulo.core.client.{Connector, ZooKeeperInstance}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.mapreduce.Job
import org.geotools.data.DataAccessFactory.Param
import org.geotools.data.DataStoreFactorySpi
import org.locationtech.geomesa.core.security.{AuthorizationsProvider, DefaultAuthorizationsProvider, FilteringAuthorizationsProvider}
import org.locationtech.geomesa.core.stats.StatWriter
import org.locationtech.geomesa.feature.FeatureEncoding

import scala.collection.JavaConversions._
import scala.util.Try

class AccumuloDataStoreFactory extends DataStoreFactorySpi {

  import org.locationtech.geomesa.core.data.AccumuloDataStoreFactory._
  import org.locationtech.geomesa.core.data.AccumuloDataStoreFactory.params._

  // this is a pass-through required of the ancestor interface
  def createNewDataStore(params: JMap[String, Serializable]) = createDataStore(params)

  def createDataStore(params: JMap[String, Serializable]) = {

    val visStr = visibilityParam.lookUp(params).asInstanceOf[String]

    val visibility =
      if (visStr == null)
        ""
      else
        visStr

    val tableName = tableNameParam.lookUp(params).asInstanceOf[String]
    val useMock = java.lang.Boolean.valueOf(mockParam.lookUp(params).asInstanceOf[String])
    val (connector, token) =
      if (params.containsKey(connParam.key)) (connParam.lookUp(params).asInstanceOf[Connector], null)
      else buildAccumuloConnector(params, useMock)

    // convert the connector authorizations into a string array - this is the maximum auths this connector can support
    val securityOps = connector.securityOperations
    val masterAuths = securityOps.getUserAuthorizations(connector.whoami)
    val masterAuthsStrings = masterAuths.map(b => new String(b))

    // get the auth params passed in as a comma-delimited string
    val configuredAuths = authsParam.lookupOpt[String](params).getOrElse("").split(",").filter(s => !s.isEmpty)

    // verify that the configured auths are valid for the connector we are using (fail-fast)
    configuredAuths.foreach(a => if(!masterAuthsStrings.contains(a) && !connector.isInstanceOf[MockConnector])
             throw new IllegalArgumentException(s"The authorization '$a' is not valid for the Accumulo connector being used"))

    // if no auths are specified we default to the connector auths
    // TODO would it be safer to default to no auths?
    val auths: List[String] =
      if (!configuredAuths.isEmpty)
        configuredAuths.toList
      else
        masterAuthsStrings.toList

    // if the user specifies an auth provider to use, try to use that impl
    val authProviderSystemProperty = Option(System.getProperty(AuthorizationsProvider.AUTH_PROVIDER_SYS_PROPERTY))

    // we wrap the authorizations provider in one that will filter based on the max auths configured for this store
    val authorizationsProvider = new FilteringAuthorizationsProvider ({
        val providers = ServiceRegistry.lookupProviders(classOf[AuthorizationsProvider]).toBuffer
        authProviderSystemProperty match {
          case Some(prop) =>
            if (classOf[DefaultAuthorizationsProvider].getName == prop)
              new DefaultAuthorizationsProvider
            else
              providers.find(_.getClass.getName == prop)
                .getOrElse {
                  val message =
                    s"The service provider class '$prop' specified by " +
                    s"${AuthorizationsProvider.AUTH_PROVIDER_SYS_PROPERTY} could not be loaded"
                  throw new IllegalArgumentException(message)
              }
          case None =>
            providers.length match {
              case 0 => new DefaultAuthorizationsProvider
              case 1 => providers.head
              case _ =>
                val message =
                  "Found multiple AuthorizationsProvider implementations. Please specify the one " +
                  "to use with the system property " +
                  s"'${AuthorizationsProvider.AUTH_PROVIDER_SYS_PROPERTY}' :: " +
                  s"${providers.map(_.getClass.getName).mkString(", ")}"
                throw new IllegalStateException(message)
            }
        }
      })

    // update the authorizations in the parameters and then configure the auth provider
    // we copy the map so as not to modify the original
    val modifiedParams = params ++ Map(authsParam.key -> auths.mkString(","))
    authorizationsProvider.configure(modifiedParams)

    val featureEncoding = featureEncParam.lookupOpt[String](params)
      .map(FeatureEncoding.withName)
      .getOrElse(DEFAULT_ENCODING)

    val collectStats = !useMock && Try(statsParam.lookUp(params).asInstanceOf[java.lang.Boolean] == true).getOrElse(true)

    if (collectStats) {
      new AccumuloDataStore(connector,
        token,
        tableName,
        authorizationsProvider,
        visibility,
        queryThreadsParam.lookupOpt(params),
        recordThreadsParam.lookupOpt(params),
        writeThreadsParam.lookupOpt(params),
        cachingParam.lookUp(params).asInstanceOf[Boolean],
        featureEncoding) with StatWriter
    } else {
      new AccumuloDataStore(connector,
        token,
        tableName,
        authorizationsProvider,
        visibility,
        queryThreadsParam.lookupOpt(params),
        recordThreadsParam.lookupOpt(params),
        writeThreadsParam.lookupOpt(params),
        cachingParam.lookUp(params).asInstanceOf[Boolean],
        featureEncoding)
    }
  }

  override def getDisplayName = "Accumulo Feature Data Store"

  override def getDescription = "Feature Data store for accumulo"

  override def getParametersInfo =
    Array(
      instanceIdParam,
      zookeepersParam,
      userParam,
      passwordParam,
      authsParam,
      visibilityParam,
      tableNameParam,
      statsParam,
      cachingParam
    )

  val pi = getParametersInfo
  def canProcess(params: JMap[String,Serializable]) =
    params.containsKey(instanceIdParam.key) || params.containsKey(connParam.key)

  override def isAvailable = true

  override def getImplementationHints = null
}

object AccumuloDataStoreFactory {
  import org.locationtech.geomesa.core.data.AccumuloDataStoreFactory.params._

  implicit class RichParam(val p: Param) {
    def lookupOpt[A](params: JMap[String, Serializable]) =
      Option(p.lookUp(params)).asInstanceOf[Option[A]]
  }

  object params {
    val connParam           = new Param("connector", classOf[Connector], "The Accumulo connector", false)
    val instanceIdParam     = new Param("instanceId", classOf[String], "The Accumulo Instance ID", true)
    val zookeepersParam     = new Param("zookeepers", classOf[String], "Zookeepers", true)
    val userParam           = new Param("user", classOf[String], "Accumulo user", true)
    val passwordParam       = new Param("password", classOf[String], "Password", true)
    val authsParam          = new Param("auths", classOf[String], "Super-set of authorizations that will be used for queries. The actual authorizations might differ, depending on the authorizations provider, but will be outside this set. Comma-delimited.", false)
    val visibilityParam     = new Param("visibilities", classOf[String], "Accumulo visibilities to apply to all written data", false)
    val tableNameParam      = new Param("tableName", classOf[String], "The Accumulo Table Name", true)
    val queryThreadsParam   = new Param("queryThreads", classOf[Integer], "The number of threads to use per query", false)
    val recordThreadsParam  = new Param("recordThreads", classOf[Integer], "The number of threads to use for record retrieval", false)
    val writeThreadsParam   = new Param("writeThreads", classOf[Integer], "The number of threads to use for writing records", false)
    val statsParam          = new Param("collectStats", classOf[java.lang.Boolean], "Toggle collection of statistics", false, java.lang.Boolean.TRUE)
    val cachingParam        = new Param("caching", classOf[java.lang.Boolean], "Toggle caching of results", false, java.lang.Boolean.TRUE)
    val mockParam           = new Param("useMock", classOf[String], "Use a mock connection (for testing)", false)
    val featureEncParam     = new Param("featureEncoding", classOf[String], "The feature encoding format (kryo, avro or text). Default is Kryo", false, "kryo")
  }

  def buildAccumuloConnector(params: JMap[String,Serializable], useMock: Boolean): (Connector, AuthenticationToken) = {
    val zookeepers = zookeepersParam.lookUp(params).asInstanceOf[String]
    val instance = instanceIdParam.lookUp(params).asInstanceOf[String]
    val user = userParam.lookUp(params).asInstanceOf[String]
    val password = passwordParam.lookUp(params).asInstanceOf[String]

    val authToken = new PasswordToken(password.getBytes)
    if(useMock) {
      (new MockInstance(instance).getConnector(user, authToken), authToken)
    } else {
      (new ZooKeeperInstance(instance, zookeepers).getConnector(user, authToken), authToken)
    }
  }

  /**
   * Return true/false whether or not the catalog referenced by these params exists
   * already (aka the accumulo table has been created)
   */
  def catalogExists(params: JMap[String,Serializable], useMock: Boolean): Boolean = {
    val (conn, _) = buildAccumuloConnector(params, useMock)
    conn.tableOperations().exists(tableNameParam.lookUp(params).asInstanceOf[String])
  }

  def configureJob(job: Job, params: JMap[String, Serializable]): Job = {
    val conf = job.getConfiguration

    conf.set(ZOOKEEPERS, zookeepersParam.lookUp(params).asInstanceOf[String])
    conf.set(INSTANCE_ID, instanceIdParam.lookUp(params).asInstanceOf[String])
    conf.set(ACCUMULO_USER, userParam.lookUp(params).asInstanceOf[String])
    conf.set(ACCUMULO_PASS, passwordParam.lookUp(params).asInstanceOf[String])
    conf.set(TABLE, tableNameParam.lookUp(params).asInstanceOf[String])
    authsParam.lookupOpt[String](params).foreach(ap => conf.set(AUTHS, ap))
    visibilityParam.lookupOpt[String](params).foreach(vis => conf.set(VISIBILITY, vis))
    featureEncParam.lookupOpt[String](params).foreach(fep => conf.set(FEATURE_ENCODING, fep))

    job
  }

  def getMRAccumuloConnectionParams(conf: Configuration): JMap[String, AnyRef] =
    Map(zookeepersParam.key   -> conf.get(ZOOKEEPERS),
      instanceIdParam.key     -> conf.get(INSTANCE_ID),
      userParam.key           -> conf.get(ACCUMULO_USER),
      passwordParam.key       -> conf.get(ACCUMULO_PASS),
      tableNameParam.key      -> conf.get(TABLE),
      authsParam.key          -> conf.get(AUTHS),
      visibilityParam.key     -> conf.get(VISIBILITY),
      featureEncParam.key     -> conf.get(FEATURE_ENCODING))
}
