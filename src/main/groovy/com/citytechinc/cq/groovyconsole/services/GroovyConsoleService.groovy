package com.citytechinc.cq.groovyconsole.services

import org.apache.sling.api.SlingHttpServletRequest

interface GroovyConsoleService {

    Map<String, String> runScript(SlingHttpServletRequest request)

    Map<String, String> saveScript(SlingHttpServletRequest request)
}
