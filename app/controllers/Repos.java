package controllers;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.core.JsonParser.NumberType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;

import play.*;
import play.mvc.*;
import play.twirl.api.Content;
import play.libs.ws.*;
import play.libs.F.Function;
import play.libs.F.Promise;
import views.html.*;
import static play.libs.Scala.emptySeq;
import static play.libs.Scala.toSeq;
import scala.collection.JavaConverters;
import play.Logger;

public class Repos extends Controller {
	
	public static final String GITHUB_BASE_URL = "https://api.github.com";
	
	public static final String PAGE_SIZE = "10";

    public static Promise<Result> list(String query, int p) {
        
    	if (StringUtils.isEmpty(query)) {
    		return Promise.promise(() -> ok(index()));
    	}
    	int page = (p < 1) ? 1 : p;
    	
    	WSRequestHolder holder = WS.url(GITHUB_BASE_URL + "/search/repositories");
    	holder
    	.setAuth("miremond", "xxx", WSAuthScheme.BASIC)
    	.setTimeout(1000)
        .setQueryParameter("q", query)
        .setQueryParameter("per_page", PAGE_SIZE);
    	holder.setQueryParameter("page", Integer.toString(page));
    	
    	
    	Promise<Result> response = holder.get().map(r -> {
    		List<String> link = r.getAllHeaders().get("Link");
			Logger.info("Link: " + link);
			int next = 1, prev = -1;
			if (link != null && !link.isEmpty()) {
				String l = link.get(0);
				next = (l.contains("rel=\"next\""))?page+1:-1;
				prev = (l.contains("rel=\"prev\""))?page-1:-1;
			}
    		JsonNode json = r.asJson();
    		scala.collection.immutable.List<JsonNode> items = JavaConverters.asScalaIteratorConverter(json.get("items").elements()).asScala().toList();
    		
    		return ok(index.render(query, items, prev, next));
    	});
    	
    	
    	return response;
    }
    
    public static Result view(String owner, String name) {
    	return TODO;
    }
    
    public static Promise<Result> viewFull(String fullname) {
    	
    	WSRequestHolder holder = WS.url(GITHUB_BASE_URL + "/repos/"+fullname+"/contributors");
    	holder
    	.setAuth("miremond", "xxx", WSAuthScheme.BASIC)
    	.setTimeout(1000);
    	
    	Promise<Result> response = holder.get().map(r -> {
    		JsonNode json = r.asJson();
    		List<String> logins = new ArrayList<>();
    		Iterator<JsonNode> it = json.elements();
    		while (it.hasNext()) {
    			logins.add(it.next().get("login").asText());
			}
    		
    		return ok(view.render(fullname, toSeq(logins)));
    	});
    	
    	
    	return response;
    }

	

	public static Content index() {
		return index.render("", emptySeq(), -1, -1);
	}

}
