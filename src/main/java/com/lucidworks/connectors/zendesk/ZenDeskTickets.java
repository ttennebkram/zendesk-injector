package com.lucidworks.connectors.zendesk;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrServer;
import org.apache.solr.common.SolrInputDocument;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
// import org.codehaus.jackson.map.JsonNode;
// import org.codehaus.jackson.map.ObjectMapper;
import com.lucidworks.dq.util.SolrUtils;

public class ZenDeskTickets {
	static String HELP_WHAT_IS_IT = "Feed Tickets from ZenDesk into Solr, or Lucid Apollo pipeline";
	static String HELP_USAGE = "ZenDesk";

	static int COMMIT_WITHIN_MS = 30000;  // 30 seconds

	/***
	 Notes on returned fields
	 "id": payload.id,
     "description": payload.description,
     "assigneeId": payload.assigneeId,
     // list of strings
     "collaboratorIds": payload.collaboratorIds,
     "comment": payload.comment,
     // cdate, ..., ttb, wtb
     "createdAt": payload.createdAt,
     // list of CustomField
     // "customFields": payload.customFields,
     "dueAt": payload.dueAt,
     "externalId": payload.externalId,
     // object: list of CustomField
     // "fields": payload.fields,
     "followupIds": payload.followupIds,
     "forumTopicId": payload.forumTopicId,
     "groupId": payload.groupId,
     "hasIncidents": payload.hasIncidents,
     "organizationId": payload.organizationId,
     "priority": payload.priority,
     "problemId": payload.problemId,
     "recipient": payload.recipient,
     "requesterId": payload.requesterId,
     // TODO: satisfaction... is object
     // "satisfactionRating": payload.satisfactionRating,
     "sharingAgreementIds": payload.sharingAgreementIds,
     // statis is object
     "status": payload.status,
     "subject": payload.subject,
     "submitterId": payload.submitterId,
     // list of strings?
     "tags": payload.tags,
     "ticketFormId": payload.ticketFormId,
     "type": payload.type,
     // cdate, ..., ttb, wtb,
     "updatedAt": payload.updatedAt,
     "url": payload.url,
     // channel, source: from, to, ref
     // "via": payload.via
	 ***/
	// NOTE: "id" has special handling when injecting into Apollo pipeline
	static String ID_FIELD = "id";
	static List<String> FIELDS_COPY_AS_IS = Arrays.asList( new String[]{
		"url",
		ID_FIELD, // "id",
		"created_at",
		"updated_at",
		"type",
		"subject",
		"description",
		"priority",
		"status",
		"recipient",
		"requester_id",
		"submitter_id",
		"assignee_id",
		"organization_id",
		"group_id",
		"forum_topic_id",
		"problem_id",
		"has_incidents",
		"due_at",
		"ticket_form_id"
		} );
	static List<String> FIELDS_SIMPLE_LIST = Arrays.asList( new String[]{
		"collaborator_ids",
		"tags",
		"sharing_agreement_ids",
		"followup_ids"
		} );
	// TODO: special fields not sent to Solr yet, and actually there are 2 flavors of special fields
	static List<String> FIELDS_SPECIAL = Arrays.asList( new String[]{
		"via",
		"custom_fields",
		"satisfaction_rating",
		"fields"
		} );
	static final Map<String, String> FIELDS_CONSTANT_VALUES = new HashMap<String , String>() {{
	    put("source", "zendesk" );
	}};

	static Options options;

	HttpSolrServer solr;

	String apolloBaseUrl;
	String apolloCollection;
	String apolloPipeline;
	String apolloIndexUrl;

	String zdServer;
	String zdUsername;
	String zdPassword;

	String zdBaseUrl;
	String zdTicketsUrl;

	public ZenDeskTickets( HttpSolrServer solr, String apolloUrl, String apolloCollection, String apolloPipeline, String zdServer, String zdUsername, String zdPassword ) {
		this.solr = solr;

		this.apolloBaseUrl = apolloUrl;
		this.apolloCollection = apolloCollection;
		this.apolloPipeline = apolloPipeline;

		if ( null != this.apolloBaseUrl ) {
			// Eg: http://localhost:8765/lucid/api/v1/
			if ( ! this.apolloBaseUrl.endsWith("/") ) {
				this.apolloBaseUrl += "/";
			}
			// Eg: either:
			// - baseUrl/index-pipelines/collName/index  -OR-
			// - baseUrl/index-pipelines/pipelineName/collName/index
			apolloIndexUrl = apolloBaseUrl + "index-pipelines/";
			if ( null!=this.apolloPipeline ) {
				apolloIndexUrl += this.apolloPipeline + "/";				
			}
			// Required, null checked in main
			apolloIndexUrl += this.apolloCollection + "/";
			apolloIndexUrl += "index";
		}
		
		this.zdServer = zdServer;
		this.zdUsername = zdUsername;
		this.zdPassword = zdPassword;

		this.zdBaseUrl = "https://" + zdServer + "/api/v2/";
		this.zdTicketsUrl = this.zdBaseUrl + "tickets.json";
	}

	void fetchAllAndSubmit() throws Exception {
    	long overallStart = System.currentTimeMillis();
		System.out.println( "Fetching initial page: '" + zdTicketsUrl + "'" );
		JsonNode content = fetchUrl( zdTicketsUrl );
        // Possible children: "tickets", "next_page", "previous_page", "count"
        JsonNode countNode = content.path("count");
        System.out.println( "Ticket Count = " + countNode );
        while ( true ) {
            JsonNode ticketsNode = content.path("tickets");
            Iterator<JsonNode> jsonTickets = ticketsNode.elements();
            processBatch( jsonTickets );
            JsonNode nextPageNode = content.path("next_page");
            if ( null==nextPageNode ) {
            	break;
            }
            String nextPageUrl = "" + nextPageNode;
            if ( nextPageUrl.startsWith("\"") ) {
            	nextPageUrl = nextPageUrl.substring( 1 );
            }
            if ( nextPageUrl.endsWith("\"") ) {
            	nextPageUrl = nextPageUrl.substring(0, nextPageUrl.length()-1);
            }
            if ( nextPageUrl.equals("null") ) {
            	break;
            }
    		System.out.println( "Fetching page: '" + nextPageUrl + "'" );
            content = fetchUrl( nextPageUrl );
            // break;
        }
    	long overallStop = System.currentTimeMillis();
    	long overallDiff = overallStop - overallStart;
    	String diffStr = NumberFormat.getNumberInstance().format( overallDiff );
    	System.out.println( "Finished, took " + diffStr + " ms" );
    }
	
	void processBatch( Iterator<JsonNode> jsonDocs ) throws Exception {
		if ( null != solr ) {
			processSolrBatch( jsonDocs );
		}
		if ( null != apolloIndexUrl ) {
			// processApolloBatch( jsonDocs );
			processApolloBatch_full( jsonDocs );
			// processApolloBatch_docbydoc( jsonDocs );
		}
	}
	void processSolrBatch( Iterator<JsonNode> jsonDocs ) throws Exception {
		Collection<SolrInputDocument> solrDocs = new ArrayList<SolrInputDocument>();
		while ( jsonDocs.hasNext() ) {
			JsonNode jdoc = jsonDocs.next();
			SolrInputDocument sdoc = jsonDoc2SolrDoc( jdoc );
			solrDocs.add( sdoc );
		}
		if ( ! solrDocs.isEmpty() ) {
			System.out.println( "Submitting " + solrDocs.size() + " docs to Solr" );
			solr.add( solrDocs, COMMIT_WITHIN_MS );
		}
		else {
			System.out.println( "WARNING: Empty Solr batch, nothing to submit" );			
		}
	}
	void processApolloBatch_full( Iterator<JsonNode> jsonDocs ) throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		ArrayNode apolloDocs = mapper.createArrayNode();
		while ( jsonDocs.hasNext() ) {
			JsonNode jdoc = jsonDocs.next();
			JsonNode adoc = jsonDoc2ApolloDoc( jdoc, mapper );
			apolloDocs.add( adoc );
		}
		if ( apolloDocs.size() > 0 ) {
			System.out.println( "Submitting " + apolloDocs.size() + " docs to Apollo indexing pipeline" );
			String payload = jsonTree2String( apolloDocs, mapper );
			postJsonContent( apolloIndexUrl, payload );
		}
		else {
			System.out.println( "WARNING: Empty Apollo batch, nothing to submit" );			
		}
	}
	void processApolloBatch_docbydoc( Iterator<JsonNode> jsonDocs ) throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		// ArrayNode apolloDocs = mapper.createArrayNode();
		int rowCounter = 0;
		while ( jsonDocs.hasNext() ) {
			JsonNode jdoc = jsonDocs.next();
			JsonNode adoc = jsonDoc2ApolloDoc( jdoc, mapper );
			// apolloDocs.add( adoc );
			rowCounter++;
			System.out.println( "Submitting row " + rowCounter + " of batch to Apollo pipeline" );
			String payload = jsonTree2String( adoc, mapper );
			postJsonContent( apolloIndexUrl, payload );
		}
//		if ( apolloDocs.size() > 0 ) {
//			System.out.println( "Submitting " + apolloDocs.size() + " docs to Apollo pipeline" );
//			String payload = jsonTree2String( apolloDocs, mapper );
//			postJsonContent( apolloIndexUrl, payload );
//		}
//		else {
//			System.out.println( "WARNING: Empty Apollo batch, nothing to submit" );			
//		}
	}

	String jsonTree2String( JsonNode tree ) throws JsonProcessingException {
		ObjectMapper mapper = new ObjectMapper();
		return jsonTree2String( tree, mapper );
	}
	String jsonTree2String( JsonNode tree, ObjectMapper mapper ) throws JsonProcessingException {
		ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();
		return writer.writeValueAsString( tree );
	}
	
	SolrInputDocument jsonDoc2SolrDoc( JsonNode jdoc ) {
		SolrInputDocument sdoc = new SolrInputDocument();
		// Copy as-is fields
		addAsIsFieldsToSolrDoc( jdoc, sdoc );
		addSimpleListFieldsToSolrDoc( jdoc, sdoc );
		addFixedValueFieldsToSolrDoc( jdoc, sdoc );
		// TODO: handle other field types
		return sdoc;
	}
	JsonNode jsonDoc2ApolloDoc( JsonNode jdoc, ObjectMapper mapper ) {
		String id = exractIdFromJsonDoc( jdoc );
		if ( null==id ) {
			throw new IllegalArgumentException( "JSON document doesn't have a valid \"id\" field." );
		}
		// ObjectMapper mapper = new ObjectMapper();
		// Create the fields subtree first
		// ArrayNode fields = mapper.createArrayNode();
		JsonNode fields = mapper.createObjectNode();
		addAsIsFieldsToApolloFields( jdoc, fields, mapper );
		addSimpleListFieldsToApolloFields( jdoc, fields, mapper );
		addFixedValueFieldsToApolloFields( jdoc, fields, mapper );
		// Create the final high level doc
		JsonNode outNode = mapper.createObjectNode();

		((ObjectNode) outNode).put( ID_FIELD, id );  // "id"

		((ObjectNode) outNode).put( "fields", fields );
		// TODO: handle other field types
		return outNode;
	}
	void addAsIsFieldsToSolrDoc( JsonNode jdoc, SolrInputDocument sdoc ) {
		for ( String fieldName : FIELDS_COPY_AS_IS ) {
	        JsonNode valueNode = jdoc.path( fieldName );
	        if ( null!=valueNode ) {
		        String valueStr = valueNode.asText();
		        if ( null!=valueStr && ! valueStr.equals("null") && valueStr.trim().length()>0 ) {
		        	sdoc.addField( fieldName, valueStr );
		        }
	        }
		}		
	}
	void addAsIsFieldsToApolloFields( JsonNode jdoc, JsonNode fields, ObjectMapper mapper ) {
		for ( String fieldName : FIELDS_COPY_AS_IS ) {
			// handled separately for Apollo
			if ( fieldName.equals(ID_FIELD) ) {
				continue;
			}
	        JsonNode inValueNode = jdoc.path( fieldName );
	        if ( null!=inValueNode ) {
		        String valueStr = inValueNode.asText();
		        if ( null!=valueStr && ! valueStr.equals("null") && valueStr.trim().length()>0 ) {
		    		JsonNode outValueInnerNode = mapper.createObjectNode();
		    		((ObjectNode) outValueInnerNode).put( "name", fieldName );
		    		((ObjectNode) outValueInnerNode).put( "value", valueStr );
		    		ArrayNode polyValuesNode = mapper.createArrayNode();
		    		polyValuesNode.add( outValueInnerNode );
		    		((ObjectNode) fields).put( fieldName, polyValuesNode );
		        }
	        }
		}		
	}
	void addSimpleListFieldsToSolrDoc( JsonNode jdoc, SolrInputDocument sdoc ) {
		for ( String fieldName : FIELDS_SIMPLE_LIST ) {
	        JsonNode listNode = jdoc.path( fieldName );
	        if ( null!=listNode ) {
	        	for ( JsonNode valueNode : listNode ) {
			        String valueStr = valueNode.asText();
			        if ( null!=valueStr && ! valueStr.equals("null") && valueStr.trim().length()>0 ) {
			        	sdoc.addField( fieldName, valueStr );
			        }	        		
	        	}
	        }
		}		
	}
	void addSimpleListFieldsToApolloFields( JsonNode jdoc, JsonNode fields, ObjectMapper mapper ) {
		for ( String fieldName : FIELDS_SIMPLE_LIST ) {
			// handled separately for Apollo
			if ( fieldName.equals(ID_FIELD) ) {
				continue;
			}
	        JsonNode listNode = jdoc.path( fieldName );
	        if ( null!=listNode ) {
	    		ArrayNode polyValuesNode = mapper.createArrayNode();
	        	for ( JsonNode valueNode : listNode ) {
			        String valueStr = valueNode.asText();
			        if ( null!=valueStr && ! valueStr.equals("null") && valueStr.trim().length()>0 ) {
			    		JsonNode outValueInnerNode = mapper.createObjectNode();
			    		((ObjectNode) outValueInnerNode).put( "name", fieldName );
			    		((ObjectNode) outValueInnerNode).put( "value", valueStr );
			    		polyValuesNode.add( outValueInnerNode );
			        }	        		
	        	}
	        	if ( polyValuesNode.size() > 0 ) {
	        		((ObjectNode) fields).put( fieldName, polyValuesNode );
	        	}
	        }
		}		
	}
	void addFixedValueFieldsToSolrDoc( JsonNode jdoc, SolrInputDocument sdoc ) {
		for ( Entry<String, String> item : FIELDS_CONSTANT_VALUES.entrySet() ) {
			sdoc.addField( item.getKey(), item.getValue() );
		}
	}
	void addFixedValueFieldsToApolloFields( JsonNode jdoc, JsonNode fields, ObjectMapper mapper ) {
		for ( Entry<String, String> item : FIELDS_CONSTANT_VALUES.entrySet() ) {
			String fieldName = item.getKey();
			String valueStr = item.getValue();
			// handled separately for Apollo
			if ( fieldName.equals(ID_FIELD) ) {
				continue;
			}
    		JsonNode outValueInnerNode = mapper.createObjectNode();
    		((ObjectNode) outValueInnerNode).put( "name", fieldName );
    		((ObjectNode) outValueInnerNode).put( "value", valueStr );
    		ArrayNode polyValuesNode = mapper.createArrayNode();
    		polyValuesNode.add( outValueInnerNode );
    		((ObjectNode) fields).put( fieldName, polyValuesNode );
		}
	}

	// Be super fussy
	String exractIdFromJsonDoc( JsonNode jdoc ) {
		String id = null;
        JsonNode idNode = jdoc.path( ID_FIELD ); // "id"
        if ( null!=idNode ) {
	        String idStr = idNode.asText();
	        if ( null!=idStr && ! idStr.equals("null") && idStr.trim().length()>0 ) {
	        	idStr = idStr.trim();
	            if ( idStr.startsWith("\"") ) {
	            	idStr = idStr.substring( 1 );
	            }
	            if ( idStr.endsWith("\"") ) {
	            	idStr = idStr.substring(0, idStr.length()-1);
	            }
	        	idStr = idStr.trim();
	        	if ( ! idStr.isEmpty() && ! idStr.equals("null") ) {
	        		id = idStr;
	        	}
	        }
        }
        return id;
	}

	JsonNode fetchUrl( String url ) throws Exception {
        // System.out.println( "FETCH: " + url );
		// URI uri = new URI( url );
		URL uri = new URL( url );
		AuthScope scope = new AuthScope( uri.getHost(), uri.getPort() );
        UsernamePasswordCredentials creds = new UsernamePasswordCredentials( zdUsername, zdPassword );
        CredentialsProvider credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials( scope, creds );
        CloseableHttpClient httpclient = HttpClients.custom()
                .setDefaultCredentialsProvider(credsProvider)
                .build();
        HttpGet get = new HttpGet( url );
        get.addHeader( "accept", "application/json" );
        // get.setParams(params);
        CloseableHttpResponse response = httpclient.execute( get );
        if ( response.getStatusLine().getStatusCode() != 200 ) {
            throw new RuntimeException("Failed : HTTP error code : "
               + response.getStatusLine().getStatusCode());
        }
        // System.out.println(response.getStatusLine());

        StringBuffer buff = new StringBuffer();
        BufferedReader br = new BufferedReader(
                new InputStreamReader((response.getEntity().getContent())));
        String output;
        while ((output = br.readLine()) != null) {
        	buff.append( output ).append( '\n' );
        }
        // System.out.println( "Fetched " + buff.length() + " chars" );
    
        response.close();
        httpclient.close();

        ObjectMapper m = new ObjectMapper();
        JsonNode rootNode = m.readTree( new String(buff) );
        // "tickets", "next_page", "previous_page", "count"
        // JsonNode countNode = rootNode.path("count");
        // System.out.println( "Count = " + countNode );
        
        return rootNode;
    
    }

	void postJsonContent( String url, String content ) throws ClientProtocolException, IOException {
        HttpClient httpClient = new DefaultHttpClient();        
        HttpPost post = new HttpPost( url );        

        post.setEntity(  new StringEntity( content, ContentType.create("application/json") )  );

        HttpResponse response = httpClient.execute( post );
        int code = response.getStatusLine().getStatusCode();
        // Apollo pipeline submit returns 204 and no text
        if ( code != 200 && code != 204 ) {
            throw new RuntimeException("Failed: HTTP error code: "
               + response.getStatusLine().getStatusCode()
               + ", reason: "
               + response.getStatusLine().getReasonPhrase()
               );
        }

        // 204 means NO response content, http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html
        if ( code != 204 ) {
	        BufferedReader rd = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
	        String line = "";
	        while ((line = rd.readLine()) != null) {
	        	//Parse our JSON response
	        	// Noggit
	            // JSONParser j = new JSONParser();
	            // JSONObject o = (JSONObject)j.parse(line);
	            // Map responseMap = (Map)o.get("response");
	            // System.out.println(responseMap.get("somevalue"));
	        }
        }
	}

	static void helpAndExit() {
		helpAndExit( null, 1 );
	}
	static void helpAndExit( String optionalError, int errorCode ) {
		HelpFormatter formatter = new HelpFormatter();
		if ( null==optionalError ) {
			// log.info( HELP_WHAT_IS_IT );
			System.out.println( HELP_WHAT_IS_IT );
		}
		else {
			// log.error( optionalError );
			System.err.println( optionalError );
		}
		formatter.printHelp( HELP_USAGE, options, true );
		System.exit( errorCode );
	}
	public static void main( String[] args ) throws Exception {
		options = new Options();
		options.addOption( "s", "solr", true, "URL for Solr, defaults to localhost:8983/solr" );
		options.addOption( "a", "apollo", true, "URL for Apollo, OVERRIDES Solr, Eg: \"http://localhost:8765/lucid/api/v1/\"" );
		options.addOption( "c", "collection", true, "Collection name for Solr or Apollo, required for Apollo" );
		options.addOption( "p", "pipeline", true, "Pipeline name for Apollo, optional" );

		options.addOption( "z", "zendesk", true, "Zendesk site, Eg: \"lucidimagination.zendesk.com\" (we add the https and /api/v2...)" );
		// -p password overlaps with -p pipeline
		// options.addOption( "u", "username", true, "Zendesk username" );
		// options.addOption( "p", "password", true, "Zendesk password" );
		 options.addOption( OptionBuilder.withLongOpt( "username" )
                 .withDescription( "Zendesk username" )
                 .hasArg()
                 .withArgName("USERNAME")
                 .create() );
		 options.addOption( OptionBuilder.withLongOpt( "password" )
                 .withDescription( "Zendesk password" )
                 .hasArg()
                 .withArgName("PASSWORD")
                 .create() );

		if ( args.length < 1 ) {
	        helpAndExit();
	    }
	    CommandLine cmd = null;
	    try {
	        CommandLineParser parser = new PosixParser();
	        // CommandLineParser parser = new DefaultParser();
	        cmd = parser.parse( options, args );
	    }
	    catch( ParseException exp ) {
	        helpAndExit( "Parsing command line failed. Reason: " + exp.getMessage(), 2 );
	    }

	    // Note:
	    // Chose Apollo or Solr
	    // If neither set, assume Solr on localhost and default port
	    // Class can actually support submitting to both Solr and Apollo
	    // but syntax check forces a choice here to avoid confusion
	    // for example, "collection" used for either/both, plus handling different

	    String apolloUrl = cmd.getOptionValue( "apollo" );
	    
	    String solrUrl = cmd.getOptionValue( "solr" );

	    if ( null!=apolloUrl && null!=solrUrl  ) {
	        helpAndExit( "Can't specify both Solr and Apollo", 3 );
	    }
	    
	    String collection = cmd.getOptionValue( "collection" );
	    String pipeline = cmd.getOptionValue( "pipeline" );
   
	    if ( null==apolloUrl && null!=pipeline ) {
	        helpAndExit( "Pipeline can only be set when submitting to Apollo", 4 );
	    }
	    
	    if ( null!=apolloUrl && null==collection ) {
	        helpAndExit( "Must specify collection when submitting to Apollo; and do NOT include it as part of the Apollo URL", 5 );
	    }

	    // Solr & Apollo
	    HttpSolrServer solr = null;
	    // Solr
	    if ( null==apolloUrl ) {
		    if ( null!=solrUrl ) {
		    	if ( null!=collection ) {
		    		if ( ! solrUrl.endsWith("/") ) {
		    			solrUrl += "/";
		    		}
		    		solrUrl += collection;
		    	}
		    	solr = SolrUtils.getServer( solrUrl );
		    }
		    else {
		    	if ( null!=collection ) {
		    		solr = SolrUtils.getServer( null, (String)null, collection );
		    	}
		    	else {
		    		solr = SolrUtils.getServer();
		    	}
		    }
	    }

	    // Zendesk info
	    String zenDeskServer = cmd.getOptionValue( "zendesk" );
	    String username = cmd.getOptionValue( "username" );
	    String password = cmd.getOptionValue( "password" );
	    if ( null==zenDeskServer || null==username || null==password ) {
	        helpAndExit( "Must specifify ZenDesk host, username and password", 2 );
	    }

		ZenDeskTickets zd = new ZenDeskTickets( solr, apolloUrl, collection, pipeline, zenDeskServer, username, password );
		zd.fetchAllAndSubmit();
	}
}
