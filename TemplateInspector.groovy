import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.DumperOptions

@Grab(group='org.yaml', module='snakeyaml', version='1.17')

def cli = new CliBuilder(usage: 'groovy TemplateInspector.groovy [options]',
                         header: 'Options:')
cli.with {
  f longOpt: 'file',
    'Template file to inspect (can be a URL). E.g. try this hello world template at https://goo.gl/M1vmvS',
    args:1, argName: 'template', type:String.class
  h longOpt: 'help', 'This usage screen'
}

def opts = cli.parse(args)
if (!opts) { return }
if (opts.help) {
  cli.usage()
  return
}


def templateUri
if (opts.file) {
  templateUri = opts.file
  def scheme = new URI(templateUri).scheme
  if (!scheme) {
      // assume a local file
      templateUri = 'file:' + templateUri
  }
} else {
  println "ERROR: Missing a file argument\n"
  cli.usage()
  System.exit(-1)
}

t = new XmlSlurper().parse(templateUri)

// create a data structure
y = [:]
y.nifi = [:]
y.nifi.templateUri = templateUri
y.nifi.templateName = t.name.text()

if (t.snippet.controllerServices.size() > 0) {
  y.controllerServices = [:]
  t.snippet.controllerServices.each { xCs ->
    def yC = y.controllerServices
    yC[xCs.name.text()] = [:]
    yC[xCs.name.text()].state = 'ENABLED'
    def xProps = xCs.properties?.entry
    if (xProps.size() > 0) {
      yC[xCs.name.text()].config = [:]
      xProps.each { xProp ->
        if (xProp.value.size() > 0) {
          yC[xCs.name.text()].config[xProp.key.text()] = xProp.value.text()
        }
      }
    }
  }
}

y.processGroups = [:]

if (t.snippet.processors.size() > 0) {
  // special handling for root-level processors
  parseGroup(t.snippet)
}

t.snippet.processGroups.each {
  parseGroup(it)
}

def parseGroup(node) {
  def pgName = node?.name.text()
  if (!pgName) {
    pgName = 'root'
  }

  y.processGroups[pgName] = [:]
  y.processGroups[pgName].processors = [:]

  parseProcessors(pgName, node)
}

def parseProcessors(groupName, node) {
  def processors = node.contents.isEmpty() ? node.processors          // root process group
                                           : node.contents.processors // regular process group
  processors.each { p ->
    y.processGroups[groupName].processors[p.name.text()] = [:]
    y.processGroups[groupName].processors[p.name.text()].config = [:]

    p.config.properties?.entry?.each {
      def c = y.processGroups[groupName].processors[p.name.text()].config
      // check if it's a UUID and try lookup the CS to get the name
      if (it.value.text() ==~ /[a-zA-Z0-9]{8}-[a-zA-Z0-9]{4}-[a-zA-Z0-9]{4}-[a-zA-Z0-9]{4}-[a-zA-Z0-9]{12}/) {
        def n = t.snippet.controllerServices.find { cs -> cs.id.text() == it.value.text() }
        assert !n.isEmpty() : "Couldn't resolve a Controller Service with ID: ${it.value.text()}"
        c[it.key.text()] = '\${' + n.name.text() + "}"
      } else if (it.value.size() > 0) {
          c[it.key.text()] = it.value.size() == 0 ? null : it.value.text()
      }
    }
  }
}

// serialize to yaml
def yamlOpts = new DumperOptions()
yamlOpts.defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
yamlOpts.prettyFlow = true
println new Yaml(yamlOpts).dump(y)
