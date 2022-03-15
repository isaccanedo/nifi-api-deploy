import groovy.json.JsonBuilder
import groovyx.net.http.RESTClient
import org.apache.http.entity.mime.MultipartEntity
import org.apache.http.entity.mime.content.StringBody
import org.yaml.snakeyaml.Yaml

import static groovy.json.JsonOutput.prettyPrint
import static groovy.json.JsonOutput.toJson
import static groovyx.net.http.ContentType.JSON
import static groovyx.net.http.ContentType.URLENC
import static groovyx.net.http.Method.POST



@Grab(group='org.codehaus.groovy.modules.http-builder',
      module='http-builder',
      version='0.7.1')
@Grab(group='org.yaml',
      module='snakeyaml',
      version='1.17')
@Grab(group='org.apache.httpcomponents',
      module='httpmime',
      version='4.2.1')

// see actual script content at the bottom of the text,
// after every implementation method. Groovy compiler likes these much better


def cli = new CliBuilder(usage: 'groovy NiFiDeploy.groovy [options]',
                         header: 'Options:')
cli.with {
  f longOpt: 'file',
    'Deployment specification file in a YAML format',
    args:1, argName:'name', type:String.class
  h longOpt: 'help', 'This usage screen'
  d longOpt: 'debug', 'Debug underlying HTTP wire communication'
  n longOpt: 'nifi-api', 'NiFi REST API (override), e.g. http://example.com:9090',
    args:1, argName:'http://host:port', type:String.class
  t longOpt: 'template', 'Template URI (override)',
    args:1, argName:'uri', type:String.class
  c longOpt: 'client-id', 'Client ID for API calls, any unique string (override)',
    args:1, argName:'id', type:String.class
}

def opts = cli.parse(args)
if (!opts) { return }
if (opts.help) {
  cli.usage()
  return
}


def deploymentSpec
if (opts.file) {
  deploymentSpec = opts.file
} else {
  println "ERROR: Missing a file argument\n"
  cli.usage()
  System.exit(-1)
}

if (opts.debug) {
  System.setProperty('org.apache.commons.logging.Log', 'org.apache.commons.logging.impl.SimpleLog')
  System.setProperty('org.apache.commons.logging.simplelog.showdatetime', 'true')
  System.setProperty('org.apache.commons.logging.simplelog.log.org.apache.http', 'DEBUG')
}

// implementation methods below

def handleUndeploy() {
  if (!conf.nifi.undeploy) {
    return
  }

  // stop & remove controller services
  // stop & remove process groups
  // delete templates

  // TODO not optimal (would rather save all CS in state), but ok for now
  conf.nifi?.undeploy?.controllerServices?.each { csName ->
    print "Undeploying Controller Service: $csName"
    def cs = lookupControllerService(csName)
    if (cs) {
      println " ($cs.id)"
      stopControllerService(cs.id)
      updateToLatestRevision()
      def resp = nifi.delete(
        path: "controller/controller-services/NODE/$cs.id",
        query: [
          clientId: client,
          version: currentRevision
        ]
      )
      assert resp.status == 200
    } else {
      println ''
    }
  }

  conf.nifi?.undeploy?.processGroups?.each { pgName ->
    println "Undeploying Process Group: $pgName"
    def pg = processGroups.findAll { it.name == pgName }
    if (pg.isEmpty()) {
      println "[WARN] No such process group found in NiFi"
      return
    }
    assert pg.size() == 1 : "Ambiguous process group name"

    def id = pg[0].id

    stopProcessGroup(id)

    // now delete it
    updateToLatestRevision()
    resp = nifi.delete(
      path: "controller/process-groups/root/process-group-references/$id",
      query: [
        clientId: client,
        version: currentRevision
      ]
    )
    assert resp.status == 200
  }

  conf.nifi?.undeploy?.templates?.each { tName ->
    println "Deleting template: $tName"
    def t = lookupTemplate(tName)
    if (t) {
      updateToLatestRevision()
      def resp = nifi.delete(
        path: "controller/templates/$t.id",
        query: [
          clientId: client,
          version: currentRevision
        ]
      )
      assert resp.status == 200
    }
  }
}

/**
  Returns a json-backed controller service structure from NiFi
*/
def lookupControllerService(String name) {
  def resp = nifi.get(
    path: 'controller/controller-services/NODE'
  )
  assert resp.status == 200

  if (resp.data.controllerServices.name.grep(name).isEmpty()) {
    return
  }

  assert resp.data.controllerServices.name.grep(name).size() == 1 :
            "Multiple controller services found named '$name'"
  // println prettyPrint(toJson(resp.data))

  def cs = resp.data.controllerServices.find { it.name == name }
  assert cs != null

  return cs
}

/**
  Returns a json-backed template structure from NiFi. Null if not found.
*/
def lookupTemplate(String name) {
  def resp = nifi.get(
    path: 'controller/templates'
  )
  assert resp.status == 200

  if (resp.data.templates.name.grep(name).isEmpty()) {
    return null
  }

  assert resp.data.templates.name.grep(name).size() == 1 :
            "Multiple templates found named '$name'"
  // println prettyPrint(toJson(resp.data))

  def t = resp.data.templates.find { it.name == name }
  assert t != null

  return t
}

def importTemplate(String templateUri) {
  println "Loading template from URI: $templateUri"
  def templateBody = templateUri.toURL().text

  nifi.request(POST) { request ->
    uri.path = '/nifi-api/controller/templates'

    requestContentType = 'multipart/form-data'
    MultipartEntity entity = new MultipartEntity()
    entity.addPart("template", new StringBody(templateBody))
    request.entity = entity

    response.success = { resp, xml ->
      switch (resp.statusLine.statusCode) {
        case 200:
          println "[WARN] Template already exists, skipping for now"
          // TODO delete template, CS and, maybe a PG
          break
        case 201:
          // grab the trailing UUID part of the location URL header
          def location = resp.headers.Location
          templateId = location[++location.lastIndexOf('/')..-1]
          println "Template successfully imported into NiFi. ID: $templateId"
          updateToLatestRevision() // ready to make further changes
          break
        default:
          throw new Exception("Error importing template")
          break
      }
    }
  }
}

def instantiateTemplate(String id) {
  updateToLatestRevision()
  def resp = nifi.post (
    path: 'controller/process-groups/root/template-instance',
    body: [
      templateId: id,
      // TODO add slight randomization to the XY to avoid hiding PG behind each other
      originX: 100,
      originY: 100,
      version: currentRevision
    ],
    requestContentType: URLENC
  )

  assert resp.status == 201
}

def loadProcessGroups() {
  println "Loading Process Groups from NiFi"
  def resp = nifi.get(
    path: 'controller/process-groups/root/process-group-references'
  )
  assert resp.status == 200
  // println resp.data
  processGroups = resp.data.processGroups
}

/**
 - read the desired pgConfig
 - locate the processor according to the nesting structure in YAML
   (intentionally not using 'search') to pick up a specific PG->Proc
 - update via a partial PUT constructed from the pgConfig
*/
def handleProcessGroup(Map.Entry pgConfig) {
  //println pgConfig

  if (!pgConfig.value) {
    return
  }

  updateToLatestRevision()

  def pgName = pgConfig.key
  def pg = processGroups.find { it.name == pgName }
  assert pg : "Processing Group '$pgName' not found in this instance, check your deployment config?"
  def pgId = pg.id

  println "Process Group: $pgConfig.key ($pgId)"
  //println pgConfig

  if (!pg.comments) {
    updateToLatestRevision()
    // update process group comments with a deployment timestamp
    def builder = new JsonBuilder()
    builder {
      revision {
        clientId client
        version currentRevision
      }
      processGroup {
        id pgId
        comments defaultComment
      }
    }

    // println builder.toPrettyString()

    updateToLatestRevision()

    resp = nifi.put (
      path: "controller/process-groups/$pgId",
      body: builder.toPrettyString(),
      requestContentType: JSON
    )
    assert resp.status == 200
  }

  // load processors in this group
  resp = nifi.get(path: "controller/process-groups/$pgId/processors")
  assert resp.status == 200

  // construct a quick map of "procName -> [id, fullUri]"
  def processors = resp.data.processors.collectEntries {
    [(it.name): [it.id, it.uri, it.comments]]
  }

  pgConfig.value.processors.each { proc ->
    // check for any duplicate processors in the remote NiFi instance
    def result = processors.findAll { remote -> remote.key == proc.key }
    assert result.entrySet().size() == 1 : "Ambiguous processor name '$proc.key'"

    def procId = processors[proc.key][0]
    def existingComments = processors[proc.key][2]

    println "Stopping Processor '$proc.key' ($procId)"
    stopProcessor(pgId, procId)

    def procProps = proc.value.config.entrySet()

    println "Applying processor configuration"
    def builder = new JsonBuilder()
    builder {
      revision {
        clientId client
        version currentRevision
      }
      processor {
        id procId
        config {
          comments existingComments ?: defaultComment
          properties {
            procProps.each { p ->
              // check if it's a ${referenceToControllerServiceName}
              def ref = p.value =~ /\$\{(.*)}/
              if (ref) {
                def name = ref[0][1] // grab the first capture group (nested inside ArrayList)
                // lookup the CS by name and get the newly generated ID instead of the one in a template
                def newCS = lookupControllerService(name)
                assert newCS : "Couldn't locate Controller Service with the name: $name"
                "$p.key" newCS.id
              } else {
                "$p.key" p.value
              }
            }
          }
        }
      }
    }

    // println builder.toPrettyString()

    updateToLatestRevision()

    resp = nifi.put (
      path: "controller/process-groups/$pgId/processors/$procId",
      body: builder.toPrettyString(),
      requestContentType: JSON
    )
    assert resp.status == 200

    // check if pgConfig tells us to start this processor
    if (proc.value.state == 'RUNNING') {
      println "Will start it up next"
      startProcessor(pgId, procId)
    } else {
      println "Processor wasn't configured to be running, not starting it up"
    }
  }

  println "Starting Process Group: $pgName ($pgId)"
  startProcessGroup(pgId)
}

def handleControllerService(Map.Entry cfg) {
  //println config
  def name = cfg.key
  println "Looking up a controller service '$name'"

  def cs = lookupControllerService(name)
  updateToLatestRevision()

  println "Found the controller service '$cs.name'. Current state is ${cs.state}."

  if (cs.state == cfg.value.state) {
    println "$cs.name is already in a requested state: '$cs.state'"
    return
  }

  if (cfg.value?.config) {
    println "Applying controller service '$cs.name' configuration"
    def builder = new JsonBuilder()
    builder {
      revision {
        clientId client
        version currentRevision
      }
      controllerService {
        id cs.id
        comments cs.comments ?: defaultComment
        properties {
          cfg.value.config.each { p ->
            "$p.key" p.value
          }
        }
      }
    }


    // println builder.toPrettyString()

    updateToLatestRevision()

    resp = nifi.put (
      path: "controller/controller-services/NODE/$cs.id",
      body: builder.toPrettyString(),
      requestContentType: JSON
    )
    assert resp.status == 200
  }


  println "Enabling $cs.name (${cs.id})"
  startControllerService(cs.id)
}

def updateToLatestRevision() {
    def resp = nifi.get(
            path: 'controller/revision'
    )
    assert resp.status == 200
    currentRevision = resp.data.revision.version
}

def stopProcessor(processGroupId, processorId) {
  _changeProcessorState(processGroupId, processorId, false)
}

def startProcessor(processGroupId, processorId) {
  _changeProcessorState(processGroupId, processorId, true)
}

private _changeProcessorState(processGroupId, processorId, boolean running) {
  updateToLatestRevision()
  def builder = new JsonBuilder()
  builder {
      revision {
          clientId client
          version currentRevision
      }
      processor {
          id processorId
          state running ? 'RUNNING' : 'STOPPED'
      }
  }

  //println builder.toPrettyString()
  resp = nifi.put (
    path: "controller/process-groups/$processGroupId/processors/$processorId",
    body: builder.toPrettyString(),
    requestContentType: JSON
  )
  assert resp.status == 200
  currentRevision = resp.data.revision.version
}

def startProcessGroup(pgId) {
  _changeProcessGroupState(pgId, true)
}

def stopProcessGroup(pgId) {
  print "Waiting for a Process Group to stop: $pgId "
  _changeProcessGroupState(pgId, false)


  int maxWait = 1000 * 30 // up to X seconds
  def resp = nifi.get(path: "controller/process-groups/$pgId/status")
  assert resp.status == 200
  long start = System.currentTimeMillis()

  // keep polling till active threads shut down, but no more than maxWait time
  while ((System.currentTimeMillis() < (start + maxWait)) &&
            resp.data.processGroupStatus.activeThreadCount > 0) {
    sleep(1000)
    resp = nifi.get(path: "controller/process-groups/$pgId/status")
    assert resp.status == 200
    print '.'
  }
  if (resp.data.processGroupStatus.activeThreadCount == 0) {
    println 'Done'
  } else {
    println "Failed to stop the processing group, request timed out after ${maxWait/1000} seconds"
    System.exit(-1)
  }
}

private _changeProcessGroupState(pgId, boolean running) {
  updateToLatestRevision()
  def resp = nifi.put(
    path: "controller/process-groups/root/process-group-references/$pgId",
    body: [
      running: running,
      client: client,
      version: currentRevision
    ],
    requestContentType: URLENC
  )
  assert resp.status == 200
}

def stopControllerService(csId) {
  _changeControllerServiceState(csId, false)
}

def startControllerService(csId) {
  _changeControllerServiceState(csId, true)
}

private _changeControllerServiceState(csId, boolean enabled) {
  updateToLatestRevision()

  if (!enabled) {
    // gotta stop all CS references first when disabling a CS
    def resp = nifi.put (
      path: "controller/controller-services/node/$csId/references",
      body: [
        clientId: client,
        version: currentRevision,
        state: 'STOPPED'
      ],
      requestContentType: URLENC
    )
    assert resp.status == 200
  }

  def builder = new JsonBuilder()
  builder {
    revision {
      clientId client
      version currentRevision
    }
    controllerService {
      id csId
      state enabled ? 'ENABLED' : 'DISABLED'
    }
  }

  // println builder.toPrettyString()

  resp = nifi.put(
      path: "controller/controller-services/NODE/$csId",
      body: builder.toPrettyString(),
      requestContentType: JSON
  )
  assert resp.status == 200
}

// script flow below

conf = new Yaml().load(new File(deploymentSpec).text)
assert conf

def nifiHostPort = opts.'nifi-api' ?: conf.nifi.url
if (!nifiHostPort) {
  println 'Please specify a NiFi instance URL in the deployment spec file or via CLI'
  System.exit(-1)
}
nifiHostPort = nifiHostPort.endsWith('/') ? nifiHostPort[0..-2] : nifiHostPort
assert nifiHostPort : "No NiFI REST API endpoint provided"

nifi = new RESTClient("$nifiHostPort/nifi-api/")
nifi.handler.failure = { resp, data ->
    resp.setData(data?.text)
    println "[ERROR] HTTP call failed. Status code: $resp.statusLine: $resp.data"
    // fail gracefully with a more sensible groovy stacktrace
    assert null : "Terminated script execution"
}


client = opts.'client-id' ?: conf.nifi.clientId
assert client : 'Client ID must be provided'

thisHost = InetAddress.localHost
defaultComment = "Last updated by '$client' on ${new Date()} from $thisHost"

currentRevision = -1 // used for optimistic concurrency throughout the REST API

processGroups = null
loadProcessGroups()

handleUndeploy()

templateId = null // will be assigned on import into NiFi

def tUri = opts.template ?: conf.nifi.templateUri
assert tUri : "Template URI not provided"
importTemplate(tUri)
instantiateTemplate(templateId)

// reload after template instantiation
loadProcessGroups()

println "Configuring Controller Services"

// controller services are dependencies of processors,
// configure them first
conf.controllerServices.each { handleControllerService(it) }

println "Configuring Process Groups and Processors"
conf.processGroups.each { handleProcessGroup(it) }

println 'All Done.'
