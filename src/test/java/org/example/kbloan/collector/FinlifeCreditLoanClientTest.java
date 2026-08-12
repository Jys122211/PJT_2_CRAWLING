package org.example.kbloan.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.kbloan.model.FinlifeGradeRates;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FinlifeCreditLoanClientTest {

    @Test
    void estimatesFourthGradeWhenLatestDisclosureOmitsGradeSix()
            throws Exception {
        JsonNode response = new ObjectMapper().readTree("""
                {
                  "result": {
                    "optionList": [
                      {
                        "fin_co_no": "0010927",
                        "fin_prdt_cd": "KB200200000001",
                        "crdt_prdt_type": "1",
                        "dcls_month": "202607",
                        "crdt_lend_rate_type": "B",
                        "crdt_grad_1": 2.98,
                        "crdt_grad_4": 3.00,
                        "crdt_grad_5": 3.01,
                        "crdt_grad_6": null
                      },
                      {
                        "fin_co_no": "0010927",
                        "fin_prdt_cd": "KB200200000001",
                        "crdt_prdt_type": "1",
                        "dcls_month": "202607",
                        "crdt_lend_rate_type": "C",
                        "crdt_grad_1": 2.19,
                        "crdt_grad_4": 2.64,
                        "crdt_grad_5": 3.16,
                        "crdt_grad_6": null
                      }
                    ]
                  }
                }
                """);

        FinlifeGradeRates rates =
                FinlifeCreditLoanClient.parseLatestRates(response);

        assertEquals("202607", rates.disclosureMonth());
        assertEquals("3.0621", rates.baseRate(4).toPlainString());
        assertEquals("2.4900", rates.spreadRate(4).toPlainString());
    }
}
