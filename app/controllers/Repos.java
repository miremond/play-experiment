package controllers;

import static play.libs.Scala.emptySeq;
import static play.libs.Scala.toSeq;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.StringJoiner;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import play.Logger;
import play.libs.F.Promise;
import play.libs.Json;
import play.libs.ws.WS;
import play.libs.ws.WSAuthScheme;
import play.libs.ws.WSRequestHolder;
import play.libs.ws.WSResponse;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import play.twirl.api.Content;
import views.html.index;
import views.html.view;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class Repos extends Controller {

    private static final String GITHUB_USER = "miremond";

    private static final String GITHUB_PASSWORD = "xxx";

    public static final String GITHUB_BASE_URL = "https://api.github.com";

    public static final String PAGE_SIZE = "10";
    
    public static final Pattern fullNamePattern = Pattern.compile("[a-zA-Z_0-9\\-\\._]+/[a-zA-Z_0-9\\-\\._]+");

    public static Promise<Result> list(String query, int p) {

        if (StringUtils.isEmpty(query)) {
            return Promise.promise(() -> ok(index()));
        }
        int page = (p < 1) ? 1 : p;

        WSRequestHolder holder = authUrl("/search/repositories").setQueryParameter("q", query)
                .setQueryParameter("per_page", PAGE_SIZE).setQueryParameter("page", Integer.toString(page));

        Promise<Result> response = holder.get().map(
                r -> {
                    validateResponse(r);

                    List<String> link = r.getAllHeaders().get("Link");
                    int next = -1, prev = -1;
                    if (link != null && !link.isEmpty()) {
                        String l = link.get(0);
                        Logger.info("Link "+link);
                        next = (l.contains("rel=\"next\"")) ? page + 1 : -1;
                        prev = (l.contains("rel=\"prev\"")) ? page - 1 : -1;
                    }
                    JsonNode json = r.asJson();
                    List<Map<String, Object>> items = new ArrayList<>();
                    if (json != null && json.get("items") != null) {
                        Iterator<JsonNode> it = json.get("items").elements();
                        while (it.hasNext()) {
                            JsonNode i = (JsonNode) it.next();
                            Map<String, Object> item = new HashMap<>();
                            item.put("name", i.get("name").asText());
                            item.put("full_name", i.get("full_name").asText());
                            item.put("stargazers_count", i.get("stargazers_count").asInt());
                            items.add(item);
                        }
                        
                        if (request().accepts("text/html")) {
                        	return ok(index.render(query, null, toSeq(items), prev, next));
                        } else {
                        	String prefix = "http://" + request().host()+"/repos?query="+query+"&page="; 
                        	StringJoiner joiner = new StringJoiner(", ", "Link [", "]");
                        	if (prev != -1) {
                        		joiner.add("<" + prefix + prev + ">; rel=\"prev\"");
                        	}
                        	if (next != -1) {
                        		joiner.add("<" + prefix + next + ">; rel=\"next\"");
                        	}
                        	
                        	response().getHeaders().put("Link", joiner.toString());
                        	return ok(Json.toJson(items));
                        }

                    } else {
                        throw error(r);
                    }

                });

        Promise<Result> recovered = response.recover(throwable -> {
        	if (request().accepts("text/html")) {
        		return ok(index(throwable.getMessage()));
        	} else {
        		return ok(throwableAsJson(throwable));
        	}
        });

        return recovered;
    }

    @SuppressWarnings("unchecked")
	public static Promise<Result> view(String fullname) {
		
		if (!fullNamePattern.matcher(fullname).matches()) {
			return Promise.promise(() -> badRequest("bad repository fullname"));
		}
	
	    WSRequestHolder holderLogins = authUrl("/repos/" + fullname + "/contributors");
	    WSRequestHolder holderCommits = authUrl("/repos/" + fullname + "/commits").setTimeout(5000).setQueryParameter(
	            "per_page", "100");
	
	    Promise<WSResponse> pLogins = holderLogins.get();
	    Promise<WSResponse> pCommits = holderCommits.get();
	
	    Promise<List<WSResponse>> all = Promise.sequence(pLogins, pCommits);
	
	    Promise<Result> response = all.map(list -> {
	        Map<String, Integer> stats = new HashMap<>();
	
	        validateResponse(list.get(0));
	
	        JsonNode jsonLogins = list.get(0).asJson();
	        Logger.info("loginJson {}", jsonLogins);
	        Iterator<JsonNode> it = jsonLogins.elements();
	        while (it.hasNext()) {
	            stats.put(it.next().get("login").asText(), 0);
	        }
	
	        validateResponse(list.get(1));
	
	        JsonNode commitsLogins = list.get(1).asJson();
	        Logger.info("commitsJson {}", commitsLogins);
	        Iterator<JsonNode> itCommits = commitsLogins.elements();
	        ArrayNode datesList = JsonNodeFactory.instance.arrayNode();
	        while (itCommits.hasNext()) {
	            JsonNode next = itCommits.next();
	
	            JsonNode commit = next.get("commit");
	            if (commit != null) {
	                JsonNode author = commit.get("author");
	                if (author != null) {
	                    JsonNode date = author.get("date");
	                    if (date != null) {
	                        String textDate = date.asText().split("T")[0];
	                        String textMsg = "";
	                        JsonNode message = commit.get("message");
	                        if (message != null) {
	                            textMsg = message.asText();
	                        }
	                        ObjectNode o = JsonNodeFactory.instance.objectNode();
	                        o.put("name", textMsg).put("date", textDate);
	                        datesList.add(o);
	                    }
	                }
	            }
	
	            JsonNode author = next.get("author");
	            if (author != null) {
	                JsonNode login = author.get("login");
	                if (login != null) {
	                    stats.merge(login.asText(), 0, (o, n) -> o + 1);
	                }
	            }
	        }
	
	        if (request().accepts("text/html")) {
	        	List<Entry<String, Integer>> statsList = new ArrayList<>(stats.entrySet());
	        	Collections.sort(statsList, (u1, u2) -> u1.getValue().compareTo(u2.getValue()));
	        	Collections.reverse(statsList);
	        	
	        	return ok(view.render(null, fullname, toSeq(statsList), datesList));
	        } else {
	        	ObjectNode jsonResponse = Json.newObject();
	        	jsonResponse.put("commiters", Json.toJson(stats.entrySet().stream().map(e -> Json.newObject().put("name", e.getKey()).put("commit_count", e.getValue())).toArray()));
	        	jsonResponse.put("timeline", datesList);
				return ok(jsonResponse);
	        }
	    });
	
	    Promise<Result> recovered = response.recover(throwable -> {
	    	if (request().accepts("text/html")) {
	    		return ok(view.render(throwable.getMessage(), fullname, emptySeq(), JsonNodeFactory.instance.arrayNode()));
	    	} else {
	    		return ok(throwableAsJson(throwable));
	    	}
	    });
	
	    return recovered;
	
	}

	public static Content index() {
        return index(null);
    }

    public static Content index(String error) {
        return index.render("", error, emptySeq(), -1, -1);
    }

    private static JsonNode throwableAsJson(Throwable throwable) {
		return Json.newObject().put("error", throwable.getMessage());
	}

	private static void validateResponse(WSResponse r) throws Exception {
        if (r.getStatus() != Http.Status.OK) {
            throw error(r);
        }
    }

    private static Exception error(WSResponse r) {
        return new Exception("Invalid response, status=" + r.getStatus() + ", message=" + r.getStatusText());
    }

    private static WSRequestHolder authUrl(String path) {
        return WS.url(GITHUB_BASE_URL + path).setAuth(GITHUB_USER, GITHUB_PASSWORD, WSAuthScheme.BASIC)
                .setTimeout(1000);
    }

}
