package controllers;

import play.*;
import play.mvc.*;

import views.html.*;

import static play.libs.Scala.emptySeq;

public class Application extends Controller {

    public static Result index() {
        return ok(Repos.index());
    }

}
