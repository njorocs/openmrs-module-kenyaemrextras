/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.kenyaemrextras.reporting.cohort.definition.evaluator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Cohort;
import org.openmrs.annotation.Handler;
import org.openmrs.module.kenyaemrextras.reporting.cohort.definition.AppointmentsAndAttritionCohortDefinition;
import org.openmrs.module.reporting.cohort.EvaluatedCohort;
import org.openmrs.module.reporting.cohort.definition.CohortDefinition;
import org.openmrs.module.reporting.cohort.definition.evaluator.CohortDefinitionEvaluator;
import org.openmrs.module.reporting.evaluation.EvaluationContext;
import org.openmrs.module.reporting.evaluation.EvaluationException;
import org.openmrs.module.reporting.evaluation.querybuilder.SqlQueryBuilder;
import org.openmrs.module.reporting.evaluation.service.EvaluationService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.HashSet;
import java.util.List;

/**
 * Evaluator for AppointmentsAndAttritionCohortDefinition Includes patients who are active on ART.
 * Provides a snapshot of a patient with regard to the last visit
 */
@Handler(supports = { AppointmentsAndAttritionCohortDefinition.class })
public class AppointmentsAndAttritionCohortDefinitionEvaluator implements CohortDefinitionEvaluator {
	
	private final Log log = LogFactory.getLog(this.getClass());
	
	@Autowired
	EvaluationService evaluationService;
	
	@Override
	public EvaluatedCohort evaluate(CohortDefinition cohortDefinition, EvaluationContext context) throws EvaluationException {
		
		AppointmentsAndAttritionCohortDefinition definition = (AppointmentsAndAttritionCohortDefinition) cohortDefinition;
		
		if (definition == null)
			return null;
		
		Cohort newCohort = new Cohort();
		
		String qry = "select t.patient_id\n"
		        + "from (select fup.patient_id,\n"
		        + "             d.patient_id                                                    as disc_patient,\n"
		        + "             d.visit_date                                                    as disc_date,\n"
		        + "             mid(max(concat(fup.visit_date, fup.next_appointment_date)), 11) as latest_next_appointment_date,\n"
		        + "             greatest(mid(max(concat(fup.visit_date, fup.refill_date)), 11),\n"
		        + "                      ifnull(max(d.visit_date), '0000-00-00'))               as refill_tca\n"
		        + "      from kenyaemr_etl.etl_patient_hiv_followup fup\n"
		        + "               left join (select k.patient_id, max(k.visit_date) as latest_fast_track_visit_date\n"
		        + "                          from kenyaemr_etl.etl_art_fast_track k\n"
		        + "                          group by k.patient_id) k on fup.patient_id = k.patient_id\n"
		        + "               join kenyaemr_etl.etl_patient_demographics p on p.patient_id = fup.patient_id\n"
		        + "               join kenyaemr_etl.etl_hiv_enrollment e on fup.patient_id = e.patient_id\n"
		        + "               left outer JOIN\n"
		        + "           (select patient_id,\n"
		        + "                   coalesce(max(date(effective_discontinuation_date)), max(date(visit_date))) visit_date,\n"
		        + "                   max(date(effective_discontinuation_date)) as                               effective_disc_date,\n"
		        + "                   discontinuation_reason\n"
		        + "            from kenyaemr_etl.etl_patient_program_discontinuation\n"
		        + "            where date(visit_date) <= date(:endDate)\n"
		        + "              and program_name = 'HIV'\n"
		        + "            group by patient_id) d on d.patient_id = fup.patient_id\n"
		        + "      where fup.visit_date <= date(:endDate)\n"
		        + "          and (fup.next_appointment_date between date(:startDate) AND date(:endDate)\n"
		        + "         or fup.refill_date between date(:startDate) AND date(:endDate))\n"
		        + "      group by patient_id\n"
		        + "      having (latest_next_appointment_date between date(:startDate) AND date(:endDate)\n"
		        + "          or refill_tca between date(:startDate) AND date(:endDate))\n"
		        + "          AND (max(e.visit_date) >= date(disc_date) or disc_patient is null or disc_date >= date(:endDate))) t;";
		
		SqlQueryBuilder builder = new SqlQueryBuilder();
		builder.append(qry);
		Date startDate = (Date) context.getParameterValue("startDate");
		Date endDate = (Date) context.getParameterValue("endDate");
		builder.addParameter("startDate", startDate);
		builder.addParameter("endDate", endDate);
		
		List<Integer> ptIds = evaluationService.evaluateToList(builder, Integer.class, context);
		newCohort.setMemberIds(new HashSet<Integer>(ptIds));
		return new EvaluatedCohort(newCohort, definition, context);
	}
}
