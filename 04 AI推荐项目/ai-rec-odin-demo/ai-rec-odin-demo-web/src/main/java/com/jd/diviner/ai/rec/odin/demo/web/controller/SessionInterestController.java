package com.jd.diviner.ai.rec.odin.demo.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.app.application.jevcid.SessionInterestJudge;
import com.jd.diviner.ai.rec.odin.demo.app.application.jevcid.SessionInterestJudge.Interest;
import com.jd.diviner.ai.rec.odin.demo.app.application.jevcid.SessionInterestJudge.Verdict;
import com.jd.diviner.ai.rec.odin.demo.web.vo.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Bypass interest endpoint. Independent of the recommendation agent loop. */
@RestController
@RequestMapping("/api/skills")
public class SessionInterestController {

    private final ObjectMapper mapper;
    private final SessionInterestJudge judge;

    public SessionInterestController(ObjectMapper mapper, SessionInterestJudge judge) {
        this.mapper = mapper;
        this.judge = judge;
    }

    @PostMapping("/session-interest")
    public Result<ObjectNode> judge(@RequestBody JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("请求体必须为对象");
        }
        Verdict verdict = judge.judge(
                body.path("profile"),
                body.path("historyBehaviors"),
                SessionInterestJudge.interestsFrom(body.path("previous")),
                SessionInterestJudge.eventsFrom(body.path("events")));
        ObjectNode data = mapper.createObjectNode();
        ArrayNode interests = data.putArray("interests");
        for (Interest interest : verdict.interests()) {
            interests.addObject()
                    .put("cid2", interest.cid2())
                    .put("cid2Name", interest.cid2Name())
                    .put("cid1Name", interest.cid1Name())
                    .put("interest", interest.interest())
                    .put("confidence", interest.confidence())
                    .put("source", interest.source())
                    .put("clicks", interest.clicks())
                    .put("exposures", interest.exposures());
        }
        data.put("summary", verdict.summary());
        data.put("source", "BYPASS_LLM");
        return new Result<>(data);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(new Result<>(400, null, exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Result<Void>> upstream(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                .body(new Result<>(502, null, exception.getMessage()));
    }
}
