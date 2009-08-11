/*
 * Licensed to the Sakai Foundation (SF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The SF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.sakaiproject.kernel.site.servlet;

import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestParameter;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.commons.json.JSONException;
import org.sakaiproject.kernel.api.personal.PersonalUtils;
import org.sakaiproject.kernel.api.site.SiteService;
import org.sakaiproject.kernel.api.site.Sort;
import org.sakaiproject.kernel.util.ExtendedJSONWriter;
import org.sakaiproject.kernel.util.JcrUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

/**
 * The <code>SiteServiceGetServlet</code>
 * 
 * @scr.component immediate="true" label="SiteMembersServlet"
 *                description="Get members servlet for site service"
 * @scr.service interface="javax.servlet.Servlet"
 * @scr.property name="service.description" value="Gets lists of members for a site"
 * @scr.property name="service.vendor" value="The Sakai Foundation"
 * @scr.property name="sling.servlet.resourceTypes" values.0="sakai/site"
 * @scr.property name="sling.servlet.methods" value="GET"
 * @scr.property name="sling.servlet.selectors" value="members"
 * 
 */
public class SiteMembersServlet extends AbstractSiteServlet {

  private static final Logger LOGGER = LoggerFactory.getLogger(SiteMembersServlet.class);
  private static final long serialVersionUID = 4874392318687088747L;

  @SuppressWarnings("unchecked")
  @Override
  protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
      throws ServletException, IOException {
    LOGGER.info("Got get to SiteServiceGetServlet");
    Node site = request.getResource().adaptTo(Node.class);
    if (site == null) {
      response.sendError(HttpServletResponse.SC_NO_CONTENT, "Couldn't find site node");
      return;
    }
    if (!getSiteService().isSite(site)) {
      String loc = request.getContextPath();
      try {
        loc = site.getPath();
      } catch (RepositoryException e) {
        // NOTHING to do here but keep going
      }
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Location (" + loc
          + ") does not represent site");
      return;
    }
    RequestParameter startParam = request.getRequestParameter(SiteService.PARAM_START);
    RequestParameter itemsParam = request.getRequestParameter(SiteService.PARAM_ITEMS);
    RequestParameter[] sortParam = request.getRequestParameters(SiteService.PARAM_SORT);
    int start = 0;
    int items = 25;
    Sort[] sort = null;
    if (startParam != null) {
      try {
        start = Integer.parseInt(startParam.getString());
      } catch (NumberFormatException e) {
        LOGGER.warn("Cant parse {} as  {} ", SiteService.PARAM_START, startParam
            .getString());
      }
    }
    if (itemsParam != null) {
      try {
        items = Integer.parseInt(itemsParam.getString());
      } catch (NumberFormatException e) {
        LOGGER.warn("Cant parse {} as  {} ", SiteService.PARAM_ITEMS, startParam
            .getString());
      }
    }
    if (sortParam != null) {
      List<Sort> sorts = new ArrayList<Sort>();
      for (RequestParameter p : sortParam) {
        try {
          sorts.add(new Sort(p.getString()));
        } catch (IllegalArgumentException ie) {
          LOGGER.warn("Invalid sort parameter: " + p.getString());
        }
      }
      sort = sorts.toArray(new Sort[] {});
    }

    try {
      LOGGER.info("Finding members for: {} ", site.getPath());
      Iterator<User> members = getSiteService().getMembers(site, start, items, sort);
      // LOGGER.info("Found members: ", members.hasNext());

      // get the list of group ids in this site
      Set<String> siteGroupIds = new HashSet<String>();
      Value[] vs = JcrUtils.getValues(site, SiteService.AUTHORIZABLE);
      for (Value value : vs) {
        if (value != null) {
          siteGroupIds.add(value.getString());
        }
      }

      try {
        ExtendedJSONWriter output = new ExtendedJSONWriter(response.getWriter());
        output.array();
        for (; members.hasNext();) {
          User u = members.next();
          Resource resource = request.getResourceResolver().resolve(
          //    "/system/userManager/user/" + u.getID());
                  PersonalUtils.getProfilePath(u.getID()));
          ValueMap map = resource.adaptTo(ValueMap.class);
          
          
          // add in the listing of member group names -
          // http://jira.sakaiproject.org/browse/KERN-276
          Set<String> groupIds = null;
          Iterator<Group> groupsIterator = u.memberOf();
          if (groupsIterator != null && groupsIterator.hasNext()) {
            groupIds = new HashSet<String>();
            for (Iterator<Group> iterator = groupsIterator; iterator.hasNext();) {
              Group group = iterator.next();
              groupIds.add(group.getID());
            }
          }

          // create the JSON object
          output.object();
          output.valueMapInternals(map);
          // add in the extra fields if there are any
          if (groupIds != null && !groupIds.isEmpty()) {
            // filter the group ids so only the ones which are part of this site are shown
            for (Iterator<String> iterator = groupIds.iterator(); iterator.hasNext();) {
              String groupId = iterator.next();
              if (groupId.startsWith("g-")) {
                // only filtering group names
                if (!siteGroupIds.contains(groupId)) {
                  iterator.remove();
                }
              }
            }
            // now output the array of group ids
            output.key(SiteService.MEMBER_GROUPS);
            output.array();
            for (String name : groupIds) {
              output.value(name);
            }
            output.endArray();
          }
          output.endObject();
        }
        output.endArray();
      } catch (JSONException e) {
        LOGGER.error(e.getMessage(), e);
        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
      } catch (RepositoryException e) {
        LOGGER.error(e.getMessage(), e);
        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
      }
    } catch (Exception e) {
      LOGGER.warn(e.getMessage(), e);
      e.printStackTrace();
      response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }
    return;
  }

}
