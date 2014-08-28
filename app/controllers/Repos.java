package controllers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Map.Entry;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.core.JsonParser.NumberType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;

import play.*;
import play.mvc.*;
import play.twirl.api.Content;
import play.libs.ws.*;
import play.libs.F.Function;
import play.libs.F.Promise;
import views.html.*;
import static play.libs.Scala.emptySeq;
import static play.libs.Scala.toSeq;
import static play.libs.Scala.asScala;
import scala.collection.JavaConverters;
import play.libs.Json;
import play.Logger;

public class Repos extends Controller {

    private static final String GITHUB_USER = "miremond";

    private static final String GITHUB_PASSWORD = "xxx";

    public static final String GITHUB_BASE_URL = "https://api.github.com";

    public static final String PAGE_SIZE = "10";

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
                    int next = 1, prev = -1;
                    if (link != null && !link.isEmpty()) {
                        String l = link.get(0);
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

                        return ok(index.render(query, null, toSeq(items), prev, next));
                    } else {
                        throw error(r);
                    }

                });

        Promise<Result> recovered = response.recover(throwable -> {
            return ok(index(throwable.getMessage()));
        });

        return recovered;
    }

    private static void validateResponse(WSResponse r) throws Exception {
        if (r.getStatus() != Http.Status.OK) {
            throw error(r);
        }
    }

    private static Exception error(WSResponse r) {
        return new Exception("Invalid response, status=" + r.getStatus() + ", message=" + r.getStatusText());
    }

    public static Result view(String owner, String name) {
        return TODO;
    }

    @SuppressWarnings("unchecked")
    public static Promise<Result> viewFull(String fullname) {

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
            Iterator<JsonNode> it = jsonLogins.elements();
            while (it.hasNext()) {
                stats.put(it.next().get("login").asText(), 0);
            }

            validateResponse(list.get(1));

            JsonNode commitsLogins = list.get(1).asJson();
            Iterator<JsonNode> itCommits = commitsLogins.elements();
            ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode();
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
                            arrayNode.add(o);
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

            List<Entry<String, Integer>> statsList = new ArrayList<>(stats.entrySet());
            Collections.sort(statsList, (u1, u2) -> u1.getValue().compareTo(u2.getValue()));
            Collections.reverse(statsList);

            return ok(view.render(null, fullname, toSeq(statsList), arrayNode));
        });

        Promise<Result> recovered = response.recover(throwable -> {
            return ok(view.render(throwable.getMessage(), fullname, emptySeq(), JsonNodeFactory.instance.arrayNode()));
        });

        return recovered;

    }

    private static WSRequestHolder authUrl(String path) {
        return WS.url(GITHUB_BASE_URL + path).setAuth(GITHUB_USER, GITHUB_PASSWORD, WSAuthScheme.BASIC)
                .setTimeout(1000);
    }

    public static Content index() {
        return index(null);
    }

    public static Content index(String error) {
        return index.render("", error, emptySeq(), -1, -1);
    }

}
