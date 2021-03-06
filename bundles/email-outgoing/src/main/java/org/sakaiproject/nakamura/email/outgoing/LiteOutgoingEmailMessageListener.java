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
package org.sakaiproject.nakamura.email.outgoing;

import com.google.common.collect.Lists;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.MultiPartEmail;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Modified;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.apache.sling.commons.scheduler.Job;
import org.apache.sling.commons.scheduler.JobContext;
import org.apache.sling.commons.scheduler.Scheduler;
import org.apache.sling.jcr.api.SlingRepository;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.sakaiproject.nakamura.api.activemq.ConnectionFactoryService;
import org.sakaiproject.nakamura.api.lite.ClientPoolException;
import org.sakaiproject.nakamura.api.lite.StorageClientException;
import org.sakaiproject.nakamura.api.lite.StorageClientUtils;
import org.sakaiproject.nakamura.api.lite.accesscontrol.AccessDeniedException;
import org.sakaiproject.nakamura.api.lite.authorizable.Authorizable;
import org.sakaiproject.nakamura.api.lite.authorizable.Group;
import org.sakaiproject.nakamura.api.lite.content.Content;
import org.sakaiproject.nakamura.api.lite.content.ContentManager;
import org.sakaiproject.nakamura.api.message.MessageConstants;
import org.sakaiproject.nakamura.api.profile.ProfileService;
import org.sakaiproject.nakamura.api.templates.TemplateService;
import org.sakaiproject.nakamura.api.user.BasicUserInfoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Date;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.Queue;
import javax.jms.Session;
import javax.mail.MessagingException;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

@Component(immediate = true, metatype = true)
public class LiteOutgoingEmailMessageListener implements MessageListener {
  private static final Logger LOGGER = LoggerFactory
      .getLogger(LiteOutgoingEmailMessageListener.class);


  @Property(value = "localhost")
  private static final String SMTP_SERVER = "sakai.smtp.server";
  @Property(intValue = 25)
  private static final String SMTP_PORT = "sakai.smtp.port";
  @Property(boolValue = false)
  private static final String SMTP_USE_TLS = "sakai.smtp.tls";
  @Property(boolValue = false)
  private static final String SMTP_USE_SSL = "sakai.smtp.ssl";
  @Property
  private static final String SMTP_AUTH_USER = "sakai.smtp.auth.user";
  @Property
  private static final String SMTP_AUTH_PASS = "sakai.smtp.auth.pass";
  @Property(intValue = 240)
  private static final String MAX_RETRIES = "sakai.email.maxRetries";
  @Property(intValue = 30)
  private static final String RETRY_INTERVAL = "sakai.email.retryIntervalMinutes";
  @Property(value = "no-reply@example.com")
  private static final String REPLY_AS_ADDRESS = "sakai.email.replyAsAddress";
  @Property(value = "Sakai OAE")
  private static final String REPLY_AS_NAME = "sakai.email.replyAsName";

  protected static final String QUEUE_NAME = "org/sakaiproject/nakamura/message/email/outgoing";

  @Reference
  protected SlingRepository repository;
  @Reference
  protected Scheduler scheduler;
  @Reference
  protected EventAdmin eventAdmin;
  @Reference
  protected ConnectionFactoryService connFactoryService;
  @Reference
  protected TemplateService templateService;
  @Reference
  protected BasicUserInfoService basicUserInfo;
  @Reference
  protected ProfileService profileService;

  /**
   * If present points to a node
   */
  protected static final String NODE_PATH_PROPERTY = "nodePath";
  /**
   * If present points to a content object.
   */
  public static final String CONTENT_PATH_PROPERTY = "contentPath";

  public static final String RECIPIENTS = "recipients";

  private Connection connection = null;
  private String smtpServer;
  private Integer smtpPort;
  private boolean useTls;
  private boolean useSsl;
  private String authUser;
  private String authPass;
  private Integer maxRetries;
  private Integer retryInterval;
  private String replyAsAddress;
  private String replyAsName;


  public LiteOutgoingEmailMessageListener() {
  }

  public LiteOutgoingEmailMessageListener(ConnectionFactoryService connFactoryService) {
    this.connFactoryService = connFactoryService;
  }

  @SuppressWarnings("unchecked")
  public void onMessage(Message message) {
    try {
      LOGGER.debug("Started handling email jms message.");

      String nodePath = message.getStringProperty(NODE_PATH_PROPERTY);
      String contentPath = message.getStringProperty(CONTENT_PATH_PROPERTY);
      Object objRcpt = message.getObjectProperty(RECIPIENTS);
      List<String> recipients = null;

      if (objRcpt instanceof List<?>) {
        recipients = (List<String>) objRcpt;
      } else if (objRcpt instanceof String) {
        recipients = new LinkedList<String>();
        String[] rcpts = StringUtils.split((String) objRcpt, ',');
        for (String rcpt : rcpts) {
          recipients.add(rcpt);
        }
      }

      if (contentPath != null && contentPath.length() > 0) {
        javax.jcr.Session adminSession = repository.loginAdministrative(null);
        org.sakaiproject.nakamura.api.lite.Session sparseSession = StorageClientUtils
            .adaptToSession(adminSession);

        try {
          ContentManager contentManager = sparseSession.getContentManager();
          Content messageContent = contentManager.get(contentPath);

          if (objRcpt != null) {
            // validate the message
            if (messageContent != null) {
              if (messageContent.hasProperty(MessageConstants.PROP_SAKAI_MESSAGEBOX)
                  && (MessageConstants.BOX_OUTBOX.equals(messageContent
                      .getProperty(MessageConstants.PROP_SAKAI_MESSAGEBOX)) 
                      || MessageConstants.BOX_PENDING.equals(messageContent.getProperty(MessageConstants.PROP_SAKAI_MESSAGEBOX)))) {
                if (messageContent.hasProperty(MessageConstants.PROP_SAKAI_MESSAGEERROR)) {
                  // We're retrying this message, so clear the errors
                  messageContent.setProperty(MessageConstants.PROP_SAKAI_MESSAGEERROR,
                      (String) null);
                }
                if (messageContent.hasProperty(MessageConstants.PROP_SAKAI_TO)
                    && messageContent.hasProperty(MessageConstants.PROP_SAKAI_FROM)) {
                  // make a commons-email message from the message
                  MultiPartEmail email = null;
                  try {
                    email = constructMessage(messageContent, recipients, adminSession,
                        sparseSession);

                    setOptions(email);
                    if (LOGGER.isDebugEnabled()) {
                      // build wrapped meesage in order to log it
                      email.buildMimeMessage();
                      logEmail(email);
                    }
                    email.send();
                  } catch (EmailException e) {
                    String exMessage = e.getMessage();
                    Throwable cause = e.getCause();

                    setError(messageContent, exMessage);
                    LOGGER.warn("Unable to send email: " + exMessage);

                    // Get the SMTP error code
                    // There has to be a better way to do this
                    boolean rescheduled = false;
                    if (cause != null && cause.getMessage() != null) {
                      String smtpError = cause.getMessage().trim();
                      try {
                        int errorCode = Integer.parseInt(smtpError.substring(0, 3));
                        // All retry-able SMTP errors should have codes starting
                        // with 4
                        scheduleRetry(errorCode, messageContent);
                        rescheduled = true;
                      } catch (NumberFormatException nfe) {
                        // smtpError didn't start with an error code, let's dig for
                        // it
                        String searchFor = "response:";
                        int rindex = smtpError.indexOf(searchFor);
                        if (rindex > -1
                            && (rindex + searchFor.length()) < smtpError.length()) {
                          int errorCode = Integer.parseInt(smtpError.substring(
                              searchFor.length(), searchFor.length() + 3));
                          scheduleRetry(errorCode, messageContent);
                          rescheduled = true;
                        }
                        else if (!rescheduled && cause.toString().contains("java.net.ConnectException")){
                          scheduleRetry(messageContent);
                          rescheduled = true;
                        }
                      }
                    }

                    if (rescheduled) {
                      LOGGER.info("Email {} rescheduled for redelivery. ", nodePath);
                    } else {
                      LOGGER
                      .error(
                          "Unable to reschedule email for delivery: "
                              + e.getMessage(), e);
                    }
                  }
                } else {
                  setError(messageContent, "Message must have a to and from set");
                }
              } else {
                setError(messageContent, "Not an outbox");
              }
              if (!messageContent.hasProperty(MessageConstants.PROP_SAKAI_MESSAGEERROR)) {
                messageContent.setProperty(MessageConstants.PROP_SAKAI_MESSAGEBOX,
                    MessageConstants.BOX_SENT);
              }
            }
          } else {
            String retval = "null";
            setError(messageContent,
                "Expected recipients to be String or List<String>.  Found " + retval);
          }
        } finally {
          if (adminSession != null) {
            adminSession.logout();
          }
        }
      }
    } catch (PathNotFoundException e) {
      LOGGER.error(e.getMessage(), e);
    } catch (RepositoryException e) {
      LOGGER.error(e.getMessage(), e);
    } catch (JMSException e) {
      LOGGER.error(e.getMessage(), e);
    } catch (EmailDeliveryException e) {
      LOGGER.error(e.getMessage());
    } catch (ClientPoolException e) {
      LOGGER.error(e.getMessage(), e);
    } catch (StorageClientException e) {
      LOGGER.error(e.getMessage(), e);
    } catch (AccessDeniedException e) {
      LOGGER.error(e.getMessage(), e);
    }
  }

  /**
   * Set transfer options on the email based on configuration of this service.
   *
   * @param email The email where to set options.
   */
  private void setOptions(MultiPartEmail email) {
    email.setHostName(smtpServer);
    email.setTLS(useTls);
    email.setSSL(useSsl);
    if (useSsl) {
      email.setSslSmtpPort(Integer.toString(smtpPort));
    } else {
      email.setSmtpPort(smtpPort);
    }

    if (!StringUtils.isBlank(authUser) && !StringUtils.isBlank(authPass)) {
      email.setAuthentication(authUser, authPass);
    }
  }

  protected MultiPartEmail constructMessage(Content contentNode, List<String> recipients,
      javax.jcr.Session session, org.sakaiproject.nakamura.api.lite.Session sparseSession)
      throws EmailDeliveryException, StorageClientException, AccessDeniedException,
      PathNotFoundException, RepositoryException, EmailException {
    MultiPartEmail email = new MultiPartEmail();


    Set<String> toRecipients = new HashSet<String>();

    toRecipients = setRecipients(recipients, sparseSession);

    String to = null;
    try {
      // set from: to the reply as address
      email.setFrom(replyAsAddress, replyAsName);

      if (toRecipients.size() == 1) {
        // set to: to the rcpt if sending to just one person
        to = convertToEmail(toRecipients.iterator().next(), sparseSession);

        email.setTo(Lists.newArrayList(new InternetAddress(to)));
      } else {
        // set to: to 'undisclosed recipients' when sending to a group of recipients
        // this mirrors what shows up in RFC's and most major MTAs
        // http://www.postfix.org/postconf.5.html#undisclosed_recipients_header
        email.addHeader("To", "undisclosed-recipients:;");
      }
    } catch (EmailException e) {
      LOGGER.error("Cannot send email. From: address as configured is not valid: {}", replyAsAddress);
    } catch (AddressException e) {
      LOGGER.error("Cannot send email. To: address is not valid: {}", to);
    }

    // if we're dealing with a group of recipients, add them to bcc: to hide email
    // addresses from the other recipients
    if (toRecipients.size() > 1) {
      for (String r : toRecipients) {
        try {
          // we don't need to copy the sender on the message
          if (r.equals(contentNode.getProperty(MessageConstants.PROP_SAKAI_FROM))) {
            continue;
          }
          email.addBcc(convertToEmail(r, sparseSession));
        } catch (EmailException e) {
          throw new EmailDeliveryException("Invalid To Address [" + r
              + "], message is being dropped :" + e.getMessage(), e);
        }
      }
    }

    if (contentNode.hasProperty(MessageConstants.PROP_SAKAI_BODY)) {
      String messageBody = String.valueOf(contentNode
          .getProperty(MessageConstants.PROP_SAKAI_BODY));
      // if this message has a template, use it
      LOGGER
          .debug("Checking for sakai:templatePath and sakai:templateParams properties on the outgoing message's node.");
      if (contentNode.hasProperty(MessageConstants.PROP_TEMPLATE_PATH)
          && contentNode.hasProperty(MessageConstants.PROP_TEMPLATE_PARAMS)) {
        Map<String, String> parameters = getTemplateProperties((String) contentNode
            .getProperty(MessageConstants.PROP_TEMPLATE_PARAMS));
        String templatePath = (String) contentNode
            .getProperty(MessageConstants.PROP_TEMPLATE_PATH);
        LOGGER.debug("Got the path '{0}' to the template for this outgoing message.",
            templatePath);
        Node templateNode = session.getNode(templatePath);
        if (templateNode.hasProperty("sakai:template")) {
          String template = templateNode.getProperty("sakai:template").getString();
          LOGGER.debug("Pulled the template body from the template node: {0}", template);
          messageBody = templateService.evaluateTemplate(parameters, template);
          LOGGER.debug("Performed parameter substitution in the template: {0}",
              messageBody);
        }
      } else {
        LOGGER
            .debug(
                "Message node '{0}' does not have sakai:templatePath and sakai:templateParams properties",
                contentNode.getPath());
      }
      try {
        email.setMsg(messageBody);
      } catch (EmailException e) {
        throw new EmailDeliveryException(
            "Invalid Message Body, message is being dropped :" + e.getMessage(), e);
      }
    }

    if (contentNode.hasProperty(MessageConstants.PROP_SAKAI_SUBJECT)) {
      email.setSubject((String) contentNode
          .getProperty(MessageConstants.PROP_SAKAI_SUBJECT));
    }

    ContentManager contentManager = sparseSession.getContentManager();
    for (String streamId : contentNode.listStreams()) {
      String description = null;
      if (contentNode.hasProperty(StorageClientUtils.getAltField(
          MessageConstants.PROP_SAKAI_ATTACHMENT_DESCRIPTION, streamId))) {
        description = (String) contentNode.getProperty(StorageClientUtils.getAltField(
            MessageConstants.PROP_SAKAI_ATTACHMENT_DESCRIPTION, streamId));
      }
      LiteEmailDataSource ds = new LiteEmailDataSource(contentManager, contentNode,
          streamId);
      try {
        email.attach(ds, streamId, description);
      } catch (EmailException e) {
        throw new EmailDeliveryException("Invalid Attachment [" + streamId
            + "] message is being dropped :" + e.getMessage(), e);
      }
    }
    return email;
  }

  private Map<String, String> getTemplateProperties(String templateParameter)
      throws IllegalStateException {
    Map<String, String> rv = new HashMap<String, String>();
    String[] values = templateParameter.split("\\|");
    for (String value : values) {
      String[] keyValuePair = value.split("=", 2);
      rv.put(keyValuePair[0], keyValuePair[1]);
    }
    return rv;
  }

  private Set<String> setRecipients(List<String> recipients,
      org.sakaiproject.nakamura.api.lite.Session session
      ) throws StorageClientException,
      AccessDeniedException {
    return setRecipients(recipients, session, new HashSet<String>(), new HashSet<String>());
  }

  private Set<String> setRecipients(List<String> recipients,
      org.sakaiproject.nakamura.api.lite.Session session,
      Set<String> newRecipients,
      Set<String> groupsAlreadyProcessed) throws StorageClientException,
      AccessDeniedException {
    for (String recipient : recipients) {
      LOGGER.debug("Checking recipient: " + recipient);
      Authorizable au = session.getAuthorizableManager().findAuthorizable(recipient);
      if (au != null && au instanceof Group) {
        // Prevent infinite recursion in cyclic group references
        if (!groupsAlreadyProcessed.contains(recipient)) {
          Group group = (Group) au;
          groupsAlreadyProcessed.add(recipient);
          // Recurse with the group members
          setRecipients(Arrays.asList(group.getMembers()),
              session,
              newRecipients,
              groupsAlreadyProcessed);
          }
      } else if (!newRecipients.contains(recipient)) {
        LOGGER.debug("Adding recipient to message delivery: " + recipient);
        newRecipients.add(recipient);
      }
    }
    return newRecipients;
  }

  private String convertToEmail(String address,
      org.sakaiproject.nakamura.api.lite.Session session) throws StorageClientException,
      AccessDeniedException, RepositoryException {
    if (address.indexOf('@') < 0) {
      Authorizable user = session.getAuthorizableManager().findAuthorizable(address);
      String emailAddress = OutgoingEmailUtils.getEmailAddress(user, session, basicUserInfo, profileService, repository);

      if (!StringUtils.isBlank(emailAddress)) {
        address = emailAddress;
      } else {
        address = address + "@" + smtpServer;
      }
    }
    return address;
  }

  protected void scheduleRetry(int errorCode, Content contentNode) {
    // All retry-able SMTP errors should have codes starting with 4
    if ((errorCode / 100) == 4) {
      scheduleRetry(contentNode);
    } else {
      LOGGER.warn("Not scheduling a retry for error code not of the form 4xx.");
    }
  }

  protected void scheduleRetry(Content contentNode) {

    long retryCount = 0;
    if (contentNode.hasProperty(MessageConstants.PROP_SAKAI_RETRY_COUNT)) {
      retryCount = StorageClientUtils.toLong(contentNode
          .getProperty(MessageConstants.PROP_SAKAI_RETRY_COUNT));
    }

    if (retryCount < maxRetries) {
      Job job = new Job() {

        public void execute(JobContext jc) {
          Map<String, Serializable> config = jc.getConfiguration();
          Properties eventProps = new Properties();
          eventProps.put(NODE_PATH_PROPERTY, config.get(NODE_PATH_PROPERTY));

          Event retryEvent = new Event(QUEUE_NAME, (Map<Object, Object>) eventProps);
          eventAdmin.postEvent(retryEvent);

        }
      };

      HashMap<String, Serializable> jobConfig = new HashMap<String, Serializable>();
      jobConfig.put(NODE_PATH_PROPERTY, contentNode.getPath());

      int retryIntervalMillis = retryInterval * 60000;
      Date nextTry = new Date(System.currentTimeMillis() + (retryIntervalMillis));

      try {
        scheduler.fireJobAt(null, job, jobConfig, nextTry);
      } catch (Exception e) {
        LOGGER.error(e.getMessage(), e);
      }
    } else {
      setError(contentNode, "Unable to send message, exhausted SMTP retries.");
    }
  }

  @Activate
  @Modified
  protected void activate(ComponentContext ctx) {
    @SuppressWarnings("rawtypes")
    Dictionary props = ctx.getProperties();

    Integer _maxRetries = PropertiesUtil.toInteger(props.get(MAX_RETRIES), -1);
    if (_maxRetries > -1 ) {
      if (diff(maxRetries, _maxRetries)) {
        maxRetries = _maxRetries;
      }
    } else {
      LOGGER.error("Maximum times to retry messages not set.");
    }

    Integer _retryInterval = PropertiesUtil.toInteger(props.get(RETRY_INTERVAL), -1);
    if (_retryInterval > -1 ) {
      if (diff(_retryInterval, retryInterval)) {
        retryInterval = _retryInterval;
      }
    } else {
      LOGGER.error("SMTP retry interval not set.");
    }

    if (maxRetries * retryInterval < 4320 /* minutes in 3 days */) {
      LOGGER.warn("SMTP retry window is very short.");
    }

    Integer _smtpPort = PropertiesUtil.toInteger(props.get(SMTP_PORT), -1);
    boolean validPort = _smtpPort != null && _smtpPort >= 0 && _smtpPort <= 65535;
    if (validPort) {
      if (diff(smtpPort, _smtpPort)) {
        smtpPort = _smtpPort;
      }
    } else {
      LOGGER.error("Invalid port set for SMTP");
    }

    String _smtpServer = PropertiesUtil.toString(props.get(SMTP_SERVER), "");
    if (!StringUtils.isBlank(_smtpServer)) {
      if (diff(smtpServer, _smtpServer)) {
        smtpServer = _smtpServer;
      }
    } else {
      LOGGER.error("No SMTP server set");
    }

    String _replyAsAddress = PropertiesUtil.toString(props.get(REPLY_AS_ADDRESS), "");
    if (!StringUtils.isBlank(_replyAsAddress)) {
      if (diff(replyAsAddress, _replyAsAddress)) {
        replyAsAddress = _replyAsAddress;
      }
    } else {
      LOGGER.error("No reply-as email address set");
    }

    String _replyAsName = PropertiesUtil.toString(props.get(REPLY_AS_NAME), "");
    if (!StringUtils.isBlank(_replyAsName)) {
      if (diff(replyAsName, _replyAsName)) {
        replyAsName = _replyAsName;
      }
    } else {
      LOGGER.error("No reply-as email name set");
    }

    useTls = PropertiesUtil.toBoolean(props.get(SMTP_USE_TLS), false);
    useSsl = PropertiesUtil.toBoolean(props.get(SMTP_USE_SSL), false);
    authUser = PropertiesUtil.toString(props.get(SMTP_AUTH_USER), "");
    authPass = PropertiesUtil.toString(props.get(SMTP_AUTH_PASS), "");

    try {
      connection = connFactoryService.getDefaultConnectionFactory().createConnection();
      Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
      Queue dest = session.createQueue(QUEUE_NAME);
      MessageConsumer consumer = session.createConsumer(dest);
      consumer.setMessageListener(this);
      connection.start();
    } catch (JMSException e) {
      LOGGER.error(e.getMessage(), e);
      if (connection != null) {
        try {
          connection.close();
        } catch (JMSException e1) {
        }
      }
    }
  }

  @Deactivate
  protected void deactivate(ComponentContext ctx) {
    if (connection != null) {
      try {
        connection.close();
      } catch (JMSException e) {
      }
    }
  }

  private void setError(Content node, String error) {
    node.setProperty(MessageConstants.PROP_SAKAI_MESSAGEERROR, error);
  }

  /**
   * Determine if there is a difference between two objects.
   * 
   * @param obj1
   * @param obj2
   * @return true if the objects are different (only one is null or !obj1.equals(obj2)).
   *         false otherwise.
   */
  private boolean diff(Object obj1, Object obj2) {
    boolean diff = true;

    boolean bothNull = obj1 == null && obj2 == null;
    boolean neitherNull = obj1 != null && obj2 != null;

    if (bothNull || (neitherNull && obj1.equals(obj2))) {
      diff = false;
    }
    return diff;
  }
  
  private void logEmail(MultiPartEmail multiPartMessage) {
    if (multiPartMessage != null) {
      MimeMessage mimeMessage = multiPartMessage.getMimeMessage();
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      try {
        mimeMessage.writeTo(new FilterOutputStream(baos));
        LOGGER.debug("Email content = \n" + baos.toString());
      } catch (IOException e) {
        LOGGER.error("failed to log email", e);
      } catch (MessagingException e) {
        LOGGER.error("failed to log email", e);
      }      
    } else {
      LOGGER.error("Email is null");
    }
  }

}
