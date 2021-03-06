package org.openmrs.module.registrationapp.page.controller;

import org.apache.commons.lang.StringUtils;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.node.ObjectNode;
import org.openmrs.Patient;
import org.openmrs.PersonAddress;
import org.openmrs.PersonAttribute;
import org.openmrs.PersonAttributeType;
import org.openmrs.PersonName;
import org.openmrs.api.context.Context;
import org.openmrs.layout.web.address.AddressSupport;
import org.openmrs.layout.web.name.NameTemplate;
import org.openmrs.messagesource.MessageSourceService;
import org.openmrs.module.appframework.domain.AppDescriptor;
import org.openmrs.module.appframework.service.AppFrameworkService;
import org.openmrs.module.appui.UiSessionContext;
import org.openmrs.module.registrationapp.model.Field;
import org.openmrs.module.registrationapp.model.NavigableFormStructure;
import org.openmrs.module.registrationapp.model.Question;
import org.openmrs.module.registrationapp.model.Section;
import org.openmrs.module.registrationcore.api.RegistrationCoreService;
import org.openmrs.module.uicommons.UiCommonsConstants;
import org.openmrs.module.uicommons.util.InfoErrorMessageUtil;
import org.openmrs.ui.framework.UiUtils;
import org.openmrs.ui.framework.annotation.BindParams;
import org.openmrs.ui.framework.annotation.MethodParam;
import org.openmrs.ui.framework.annotation.SpringBean;
import org.openmrs.ui.framework.fragment.FragmentRequest;
import org.openmrs.ui.framework.page.PageModel;
import org.openmrs.ui.framework.session.Session;
import org.openmrs.validator.PatientValidator;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

public class RegisterPatientPageController {

    private static final String REGISTRATION_SECTION_EXTENSION_POINT = "org.openmrs.module.registrationapp.section";
    private static final String REGISTRATION_FORM_STRUCTURE = "formStructure";

    public void get(UiSessionContext sessionContext, PageModel model,
                    @RequestParam("appId") AppDescriptor app,
                    @SpringBean("nameTemplateGivenFamily") NameTemplate nameTemplate) throws Exception {

        sessionContext.requireAuthentication();
        NavigableFormStructure formStructure = buildFormStructure(app);

        addModelAttributes(model, app, nameTemplate);
    }

    public NavigableFormStructure buildFormStructure(AppDescriptor app) throws IOException {
        NavigableFormStructure formStructure = new NavigableFormStructure();

        ArrayNode sections = (ArrayNode) app.getConfig().get("sections");
        for (JsonNode i : sections) {
            ObjectNode config = (ObjectNode) i;

            ObjectMapper objectMapper = new ObjectMapper();
            Section section = objectMapper.convertValue(config, Section.class);

            if (section.getQuestions() != null) {
                for (Question question : section.getQuestions()) {
                    if (question.getFields() != null) {
                        for (Field field : question.getFields()) {
                            ObjectNode widget = field.getWidget();
                            String providerName = (String) widget.get("providerName").getTextValue();
                            String fragmentId = (String) widget.get("fragmentId").getTextValue();
                            FragmentRequest fragmentRequest = new FragmentRequest(providerName, fragmentId);
                            field.setFragmentRequest(fragmentRequest);
                        }
                    }
                }
            }

            formStructure.addSection(section);
        }

        return formStructure;
    }

    public String post(UiSessionContext sessionContext,
                       PageModel model,
                       BindingResult errors,
                       @RequestParam("appId") AppDescriptor app,
                       @SpringBean("registrationCoreService") RegistrationCoreService registrationService,
                       @SpringBean("appFrameworkService") AppFrameworkService appFrameworkService,
                       @SpringBean("messageSourceService") MessageSourceService messageSourceService,
                       @ModelAttribute("patient") @BindParams Patient patient,
                       @ModelAttribute("personName") @BindParams PersonName name,
                       @ModelAttribute("personAddress") @BindParams PersonAddress address,
                       @SpringBean("nameTemplateGivenFamily") NameTemplate nameTemplate, HttpServletRequest request,
                       Session Session, UiUtils ui) throws Exception {

        //The framework isn't passing in the BindingResult object
        if(errors == null)
            errors = new BeanPropertyBindingResult(patient, "");

        new PatientValidator().validate(patient, errors);
        if (errors.hasErrors()) {
            model.addAttribute("errors", errors);
            StringBuffer errorMessage = new StringBuffer(messageSourceService.getMessage("error.failed.validation"));
            errorMessage.append("<ul>");
            for (ObjectError error : errors.getAllErrors()) {
                errorMessage.append("<li>");
                errorMessage.append(messageSourceService.getMessage(error.getCode(), error.getArguments(),
                        error.getDefaultMessage(), null));
                errorMessage.append("</li>");
            }
            errorMessage.append("</ul>");
            Session.setAttribute(UiCommonsConstants.SESSION_ATTRIBUTE_ERROR_MESSAGE, errorMessage.toString());
            addModelAttributes(model, app, nameTemplate);

            return null;//redisplay the form to show validation messages
        }

        NavigableFormStructure formStructure = buildFormStructure(app);

        patient.addName(name);
        patient.addAddress(address);

        if(formStructure!=null){
            List<Field> fields = formStructure.getFields();
            if(fields!=null && fields.size()>0){
                patient = parseRequestFields(patient, request, fields);
            }
        }

        //TODO create encounters
        patient = registrationService.registerPatient(patient, null, sessionContext.getSessionLocation());

        InfoErrorMessageUtil.flashInfoMessage(request.getSession(), ui.message("registrationapp.createdPatientMessage", patient.getPersonName()));

        String redirectUrl = app.getConfig().get("afterCreatedUrl").getTextValue();
        redirectUrl = redirectUrl.replaceAll("\\{\\{patientId\\}\\}", patient.getId().toString());
        return "redirect:" + redirectUrl;
    }


    private Patient parseRequestFields(Patient patient, HttpServletRequest request, List<Field> fields) {
        if(fields!=null && fields.size()>0){
            for (Field field : fields) {
                String parameterValue = request.getParameter(field.getFormFieldName());
                if(StringUtils.isNotBlank(parameterValue)){
                    if(StringUtils.equals(field.getType(), "personAttribute")){
                        PersonAttributeType personAttributeByUuid = Context.getPersonService().getPersonAttributeTypeByUuid(field.getUuid());
                        if(personAttributeByUuid!=null){
                            PersonAttribute attribute = new PersonAttribute(personAttributeByUuid, parameterValue);
                            patient.addAttribute(attribute);
                        }
                    }
                }
            }
        }
        return patient;
    }

    public void addModelAttributes(PageModel model, AppDescriptor app, NameTemplate nameTemplate) throws Exception {
        NavigableFormStructure formStructure = buildFormStructure(app);

        model.addAttribute("formStructure", formStructure);
        model.addAttribute("nameTemplate", nameTemplate);
        model.addAttribute("addressTemplate", AddressSupport.getInstance().getAddressTemplate().get(0));
        model.addAttribute("enableOverrideOfAddressPortlet",
                Context.getAdministrationService().getGlobalProperty("addresshierarchy.enableOverrideOfAddressPortlet", "false"));
    }


}
