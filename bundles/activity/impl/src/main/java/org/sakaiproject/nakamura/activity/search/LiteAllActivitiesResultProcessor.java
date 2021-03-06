/**
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
package org.sakaiproject.nakamura.activity.search;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.commons.json.JSONException;
import org.apache.sling.commons.json.io.JSONWriter;
import org.apache.sling.jcr.resource.JcrResourceConstants;
import org.sakaiproject.nakamura.api.activity.ActivityConstants;
import org.sakaiproject.nakamura.api.lite.Session;
import org.sakaiproject.nakamura.api.lite.StorageClientException;
import org.sakaiproject.nakamura.api.lite.StorageClientUtils;
import org.sakaiproject.nakamura.api.lite.accesscontrol.AccessDeniedException;
import org.sakaiproject.nakamura.api.lite.authorizable.Authorizable;
import org.sakaiproject.nakamura.api.lite.authorizable.AuthorizableManager;
import org.sakaiproject.nakamura.api.lite.content.Content;
import org.sakaiproject.nakamura.api.lite.content.ContentManager;
import org.sakaiproject.nakamura.api.search.solr.Query;
import org.sakaiproject.nakamura.api.search.solr.Result;
import org.sakaiproject.nakamura.api.search.solr.SolrSearchConstants;
import org.sakaiproject.nakamura.api.search.solr.SolrSearchException;
import org.sakaiproject.nakamura.api.search.solr.SolrSearchResultProcessor;
import org.sakaiproject.nakamura.api.search.solr.SolrSearchResultSet;
import org.sakaiproject.nakamura.api.search.solr.SolrSearchServiceFactory;
import org.sakaiproject.nakamura.api.user.BasicUserInfoService;
import org.sakaiproject.nakamura.util.ExtendedJSONWriter;
import org.sakaiproject.nakamura.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component(immediate = true, metatype = true)
@Service(value = SolrSearchResultProcessor.class)
@Properties(value = { @Property(name = "service.vendor", value = "The Sakai Foundation"),
    @Property(name =  SolrSearchConstants.REG_PROCESSOR_NAMES, value = "LiteAllActivities") })
public class LiteAllActivitiesResultProcessor implements SolrSearchResultProcessor {


  private static final Logger LOGGER = LoggerFactory
      .getLogger(LiteAllActivitiesResultProcessor.class);

  @Reference
  protected SolrSearchServiceFactory searchServiceFactory;

  @Reference
  protected BasicUserInfoService basicUserInfoService;

  public void writeResult(SlingHttpServletRequest request, JSONWriter write, Result result)
      throws JSONException {
    Session session = StorageClientUtils.adaptToSession(request.getResourceResolver()
        .adaptTo(javax.jcr.Session.class));
    try {
      ContentManager contentManager = session.getContentManager();
      AuthorizableManager authorizableManager = session.getAuthorizableManager();
      String path = result.getPath();
      Content activityNode = contentManager.get(path);
      if (activityNode != null ) {
        String sourcePath = (String) activityNode.getProperty(ActivityConstants.PARAM_SOURCE);
        LOGGER.info("Processing {} {} Source = {} ",new Object[]{path, activityNode.getProperty(JcrResourceConstants.SLING_RESOURCE_TYPE_PROPERTY), sourcePath });
        Content contentNode = null;
        try {
          contentNode = contentManager.get(sourcePath);
        } catch ( AccessDeniedException e ) {
          LOGGER.debug(e.getMessage(),e);
        }
        write.object();
        if ( contentNode != null ) {
          ExtendedJSONWriter.writeValueMapInternals(write, contentNode.getProperties());
          ExtendedJSONWriter.writeValueMapInternals(write, StorageClientUtils.getFilterMap(
              activityNode.getProperties(), null, null, contentNode.getProperties().keySet(), true));
        } else {
          write.key("_sourceMissing");
          write.value(true);
          ExtendedJSONWriter.writeValueMapInternals(write, activityNode.getProperties());
        }

        writeUsers(write, authorizableManager, activityNode);

        if (contentNode != null) {
          // KERN-1867 Activity feed should return more data about a group
          if ("sakai/group-home".equals(contentNode.getProperty("sling:resourceType"))) {
            try {
              final Authorizable group = authorizableManager.findAuthorizable(PathUtils
                      .getAuthorizableId(contentNode.getPath()));
              final Map<String, Object> basicUserInfo = basicUserInfoService
                      .getProperties(group);
              if (basicUserInfo != null) {
                write.key("profile");
                ExtendedJSONWriter.writeValueMap(write, basicUserInfo);
              }
            } catch (AccessDeniedException e) {
              LOGGER.debug(e.getMessage(), e);
            }
          }
          // KERN-1864 Return comment in activity feed
          if ("sakai/pooled-content".equals(contentNode.getProperty("sling:resourceType"))) {
            if ("CONTENT_ADDED_COMMENT".equals(activityNode.getProperty("sakai:activityMessage"))) {
              // expecting param ActivityConstants.PARAM_SOURCE to contain the path
              // from the content node to the comment node for this activity.
              if (activityNode.hasProperty(ActivityConstants.PARAM_SOURCE)) {
                String sakaiActivitySource = (String) activityNode.getProperty(ActivityConstants.PARAM_SOURCE);
                if (sakaiActivitySource != null ) {
                  // confirm comment path is related to the current content node.
                  if (sakaiActivitySource.startsWith(contentNode.getPath())) {
                    Content commentNode = contentManager.get(sakaiActivitySource);
                    if (commentNode != null) {
                      write.key("sakai:comment-body");
                      write.value(commentNode.getProperty("comment"));
                    }
                  }
                }
              }
            }
          }
        }
        write.endObject();
      } else {
        ExtendedJSONWriter.writeValueMap(write, result.getProperties());
      }
    } catch (AccessDeniedException e) {
      LOGGER.debug(e.getMessage(), e);
    } catch (StorageClientException e) {
      LOGGER.warn(e.getMessage(), e);
    }
  }

  private void writeUsers(JSONWriter write, AuthorizableManager authorizableManager, Content activityNode) throws JSONException, StorageClientException {
    write.key("users");
    write.object();

    List<String> users = new ArrayList<String>();

    // actor
    users.add((String) activityNode.getProperty(ActivityConstants.PARAM_ACTOR_ID));

    // audience (if present)
    Object audienceObj = activityNode.getProperty(ActivityConstants.PARAM_AUDIENCE_ID);
    if (audienceObj != null) {
      if (audienceObj instanceof String[]) {
        String[] audience = (String[]) audienceObj;
        Collections.addAll(users, audience);
      } else if (audienceObj instanceof String) {
        users.add(audienceObj.toString());
      }
    }

    // write all the users
    for (String userID : users) {
      write.key(userID);
      write.object();
      try {
        ExtendedJSONWriter.writeValueMapInternals(write, basicUserInfoService
            .getProperties(authorizableManager.findAuthorizable(userID)));
      } catch (AccessDeniedException e) {
        LOGGER.debug(e.getMessage(), e);
      }
      write.endObject();
    }

    write.endObject();
  }

  /**
   * {@inheritDoc}
   * 
   * @see org.sakaiproject.nakamura.api.search.solr.SolrSearchBatchResultProcessor#getSearchResultSet(org.apache.sling.api.SlingHttpServletRequest,
   *      org.sakaiproject.nakamura.api.search.solr.Query)
   */
  public SolrSearchResultSet getSearchResultSet(SlingHttpServletRequest request,
      Query query) throws SolrSearchException {

    return searchServiceFactory.getSearchResultSet(request, query);
  }

}
