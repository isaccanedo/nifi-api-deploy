## Note: this was originally created for NiFi 0.x. REST APIs and concepts have changed significantly in NiFi 1.x
# NiFi Deployment Automation

 - Deploy & configure NiFi templates with a touch of a button (or, rather, a single command)
 - Specify a URI to fetch a Template from - meaning it can be a local file system, remote HTTP URL, or any other exotic location for which you have a URLHandler installed
 - Describe NiFi state and configuration properties for things you want tweaked in a template. YAML format came out to be the cleanest and most usable option (YAML is a subset of JSON)
 - (Recommended) Tell NiFi what things are in your way and have them undeployed as part of the process. Good idea if one wants a deployment to be **idempotent**.

# NiFi Template Inspector

A utility to list available components and properties in a template. Also serves as a great starting point to customize a deployment. See https://github.com/aperepel/nifi-api-deploy/wiki

### Wait, what's a template?
Template is a NiFi means to share and exchange re-usable parts of a flow. It can be trivial or very complex, works the same way all along. Documentation at https://nifi.apache.org/docs/nifi-docs/html/user-guide.html#templates

# 1-minute How-To
```
git clone https://github.com/aperepel/nifi-api-deploy.git
cd nifi-api-deploy

# edit nifi-deploy.yml and point nifi.url to your NiFi instance or cluster

groovy NiFiDeploy.groovy --file nifi-deploy.yml
...
# after deployment completes
nifi-api-deploy ♨ > curl http://192.168.99.102:10000
Dynamically Configured NiFi!

# bonus item, see 'undeploy' in action
groovy NiFiDeploy.groovy --file nifi-deploy.yml
```



When things finish one ends up with the following in NiFi:

 - `Hello_NiFi_Web_Service` template imported. See more here: https://cwiki.apache.org/confluence/display/NIFI/Example+Dataflow+Templates
 - Template's listen port and service return message reconfigured as per our deployment recipe
 - Template is instantiated and its  `Processing Group` is added to the canvas
 - Things are started up and an HTTP endpoint is listening on port 10000

![Image of the Template Running](/assets/HelloNiFi_screenshot.png)

# 5-minute Introduction

The `nifi-deploy.yml` has several major sections:

 - Basics, like your NiFi address and where to get the template from
 - Undeploy instructions, for idempotent script runs
 - Controller Services to be instantiated
 - Processor configurations

Best way to grasp things is to dissect the YAML file:
```
nifi:
  url: http://192.168.99.103:9091

  # when making changes via API, need a unique client ID, can be anything
  clientId: Deployment Script v1

  # Where to fetch the actual template XML data from
  # Escape complex URLs with quotes
  templateUri: "any file: , http://..., etc URL"

  # Tell NiFi we want some things removed to make way for this (re-) deployment
  undeploy:

    # Names of controller services to remove. Ignores any missing ones
    controllerServices:
      - StandardHttpContextMap
      - SomeOtherControllerService

    # Names of process groups to remove. These are in your template
    processGroups:
      - Hello NiFi Web Service

    # Template names to remove. Because we're updating with a new version
    templates:
      - Hello NiFi Web Service
```


Next, one describes what configuration changes need to be applied to the template in this deployment:
```
# Instantiate these controller services, our template uses them
controllerServices:
  StandardHttpContextMap:
    state: ENABLED

# Processors belong to process groups.
# This way random ones won't be picked up (unlike a search api,
# which returns every occurence)
processGroups:

  # Empty in this case, as our template puts everything in a group
  root: ~

  # Process group name from a template
  Hello NiFi Web Service:

    # processors we want to reconfigure from template defaults
    processors:

      # processor by name
      Receive request and data:
        state: RUNNING

        # These match the Properties tab in the processor UI
        config:
          Listening Port: 10000

      # another processor, but name is escaped with quotes
      "Update Request Body with a greeting!":
        config:
          Replacement Value: Dynamically Configured NiFi!

```

# Troubleshooting
### Proxy
The script automatically downloads several dependencies from a Maven central repository (via Grape annotation). If you are behind a firewall, and can't reach that server directly, try adding these system properties on the command line:
```
groovy -Dhttp.proxyHost=myproxy.mycompany.com -Dhttp.proxyPort=3128 NiFiDeploy.groovy
```
See more at http://docs.oracle.com/javase/8/docs/technotes/guides/net/proxies.html

### REST API Issues
Start troubleshooting by enabling the HTTP debug option via the `--debug`
