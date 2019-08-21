package eu.europa.esig.dss.web.controller;

import java.io.InputStream;
import java.util.Date;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.SessionAttributes;

import eu.europa.esig.dss.diagnostic.DiagnosticDataFacade;
import eu.europa.esig.dss.diagnostic.jaxb.XmlCertificate;
import eu.europa.esig.dss.diagnostic.jaxb.XmlDiagnosticData;
import eu.europa.esig.dss.policy.EtsiValidationPolicy;
import eu.europa.esig.dss.policy.ValidationPolicyFacade;
import eu.europa.esig.dss.utils.Utils;
import eu.europa.esig.dss.validation.executor.CertificateProcessExecutor;
import eu.europa.esig.dss.validation.executor.DefaultCertificateProcessExecutor;
import eu.europa.esig.dss.validation.executor.DefaultSignatureProcessExecutor;
import eu.europa.esig.dss.validation.executor.ProcessExecutor;
import eu.europa.esig.dss.validation.reports.AbstractReports;
import eu.europa.esig.dss.web.exception.InternalServerException;
import eu.europa.esig.dss.web.model.ReplayDiagForm;


@Controller
@SessionAttributes({ "simpleReportXml", "detailedReportXml", "diagnosticDataXml" })
@RequestMapping(value = "/replay-diagnostic-data")
public class ReplayDiagController extends AbstractValidationController {

	private static final Logger LOG = LoggerFactory.getLogger(ReplayDiagController.class);
	
	private static final String REPLAY_TILE = "replay-diagnostic-data";
	private static final String VALIDATION_RESULT_TILE = "validation_result";
	
	@RequestMapping(method = RequestMethod.GET)
	public String showReplayDiagForm(Model model, HttpServletRequest request) {
		ReplayDiagForm replayForm = new ReplayDiagForm();
		replayForm.setDefaultPolicy(true);
		model.addAttribute("replayDiagForm", replayForm);
		return REPLAY_TILE;
	}
	
	@RequestMapping(method = RequestMethod.POST)
	public String validate(@ModelAttribute("replayDiagForm") @Valid ReplayDiagForm replayDiagForm, BindingResult result, Model model) {
		if (result.hasErrors()) {
			return REPLAY_TILE;
		}

		XmlDiagnosticData dd = null;
		try (InputStream is = replayDiagForm.getDiagnosticFile().getInputStream()) {
			dd = DiagnosticDataFacade.newFacade().unmarshall(is);
		} catch (Exception e) {
			LOG.warn("Unable to parse the diagnostic data", e);
			throw new InternalServerException("Error while creating diagnostic data from given file");
		}
			
		// Determine if Diagnostic data is a certificate or signature validation
		ProcessExecutor<? extends AbstractReports> executor;
		executor = Utils.isCollectionEmpty(dd.getSignatures()) ? new DefaultCertificateProcessExecutor()
				: new DefaultSignatureProcessExecutor();
		executor.setDiagnosticData(dd);
		
		// Set validation date
		Date validationDate = (replayDiagForm.isResetDate()) ? new Date() : dd.getValidationDate();
		executor.setCurrentTime(validationDate);
		
		// Set policy
		if (!replayDiagForm.isDefaultPolicy() && ((replayDiagForm.getPolicyFile() != null) && !replayDiagForm.getPolicyFile().isEmpty())) {
			try (InputStream policyIs = replayDiagForm.getPolicyFile().getInputStream()) {
				executor.setValidationPolicy(new EtsiValidationPolicy(ValidationPolicyFacade.newFacade().unmarshall(policyIs)));
			} catch (Exception e) {
				LOG.warn("Unable to parse the provided validation policy", e);
				throw new InternalServerException("Error while loading the provided validation policy");
			}
		} else {
			try {
				executor.setValidationPolicy(ValidationPolicyFacade.newFacade().getDefaultValidationPolicy());
			} catch (Exception e) {
				LOG.warn("Unable to parse the default validation policy", e);
				throw new InternalServerException("Error while loading the default validation policy");
			}
		}
		
		// If applicable, set certificate id
		if(executor instanceof CertificateProcessExecutor) {
			((CertificateProcessExecutor) executor).setCertificateId(getCertificateId(dd));
		}
		
		AbstractReports reports = executor.execute();
		setAttributesModels(model, reports);
		
		return VALIDATION_RESULT_TILE;
		
	}

	private String getCertificateId(XmlDiagnosticData dd) {
		String certificateId = null;
		int longestChain= 0;
		List<XmlCertificate> usedCertificates = dd.getUsedCertificates();
		for (XmlCertificate xmlCertificate : usedCertificates) {
			int chainSize = Utils.collectionSize(xmlCertificate.getCertificateChain());
			if (longestChain == 0 || longestChain < chainSize) {
				longestChain = chainSize;
				certificateId = xmlCertificate.getId();
			}
		}
		return certificateId;
	}
	
}