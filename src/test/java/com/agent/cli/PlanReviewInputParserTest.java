package com.agent.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanReviewInputParserTest {

    @Test
    void emptyInputMeansExecute() {
        PlanReviewInputParser.Decision decision = PlanReviewInputParser.parse("   ");
        assertEquals(PlanReviewInputParser.DecisionType.EXECUTE, decision.type());
    }

    @Test
    void cancelCommandMeansCancel() {
        PlanReviewInputParser.Decision decision = PlanReviewInputParser.parse("/cancel");
        assertEquals(PlanReviewInputParser.DecisionType.CANCEL, decision.type());
    }

    @Test
    void escCharacterMeansCancel() {
        PlanReviewInputParser.Decision decision = PlanReviewInputParser.parse("\u001B");
        assertEquals(PlanReviewInputParser.DecisionType.CANCEL, decision.type());
    }

    @Test
    void otherTextMeansSupplement() {
        PlanReviewInputParser.Decision decision = PlanReviewInputParser.parse("请先加一个 README 检查步骤");
        assertEquals(PlanReviewInputParser.DecisionType.SUPPLEMENT, decision.type());
        assertEquals("请先加一个 README 检查步骤", decision.feedback());
    }
}
