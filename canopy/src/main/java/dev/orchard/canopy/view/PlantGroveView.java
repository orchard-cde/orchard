package dev.orchard.canopy.view;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;
import dev.orchard.api.dto.CreateGroveRequest;
import dev.orchard.api.service.GroveService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

@Route(value = "plant", layout = MainLayout.class)
@PageTitle("Plant Grove | Orchard Canopy")
public class PlantGroveView extends VerticalLayout {

    private final GroveService groveService;

    private TextField repositoryUrl;
    private TextField branch;
    private TextField name;
    private ComboBox<String> machineSize;

    @Autowired
    public PlantGroveView(GroveService groveService) {
        this.groveService = groveService;
        setSizeFull();
        setPadding(true);
        setMaxWidth("800px");

        add(createHeader());
        add(createForm());
    }

    private VerticalLayout createHeader() {
        H2 title = new H2("Plant a New Grove");
        title.addClassNames(LumoUtility.Margin.NONE);

        Paragraph description = new Paragraph(
            "A grove is your personal development workspace. " +
            "Enter a repository URL to get started, and we'll prepare a fresh environment for you."
        );
        description.addClassNames(LumoUtility.TextColor.SECONDARY);

        VerticalLayout header = new VerticalLayout(title, description);
        header.setPadding(false);
        header.setSpacing(false);
        return header;
    }

    private VerticalLayout createForm() {
        FormLayout form = new FormLayout();
        form.setResponsiveSteps(
            new FormLayout.ResponsiveStep("0", 1),
            new FormLayout.ResponsiveStep("500px", 2)
        );

        repositoryUrl = new TextField("Repository URL");
        repositoryUrl.setPlaceholder("https://github.com/user/repo");
        repositoryUrl.setRequired(true);
        repositoryUrl.setWidthFull();
        repositoryUrl.setPrefixComponent(new Icon(VaadinIcon.CODE));

        branch = new TextField("Branch");
        branch.setValue("main");
        branch.setPlaceholder("main");
        branch.setPrefixComponent(new Icon(VaadinIcon.ROAD_BRANCH));

        name = new TextField("Grove Name (optional)");
        name.setPlaceholder("Auto-generated from repo name");

        machineSize = new ComboBox<>("Machine Size");
        machineSize.setItems("small", "medium", "large");
        machineSize.setValue("small");
        machineSize.setItemLabelGenerator(size -> switch (size) {
            case "small" -> "Small (2 CPU, 4GB RAM, 20GB disk)";
            case "medium" -> "Medium (4 CPU, 8GB RAM, 40GB disk)";
            case "large" -> "Large (8 CPU, 16GB RAM, 80GB disk)";
            default -> size;
        });

        form.add(repositoryUrl, 2);
        form.add(branch, name);
        form.add(machineSize, 2);

        Button plantButton = new Button("Plant Grove", new Icon(VaadinIcon.PLUS_CIRCLE));
        plantButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_LARGE);
        plantButton.addClickListener(e -> plantGrove());

        Button cancelButton = new Button("Cancel");
        cancelButton.addClickListener(e -> getUI().ifPresent(ui -> ui.navigate(GrovesView.class)));

        HorizontalLayout buttons = new HorizontalLayout(plantButton, cancelButton);
        buttons.addClassNames(LumoUtility.Margin.Top.LARGE);

        VerticalLayout formContainer = new VerticalLayout(form, buttons);
        formContainer.setPadding(false);
        formContainer.addClassNames(
            LumoUtility.Background.CONTRAST_5,
            LumoUtility.BorderRadius.MEDIUM,
            LumoUtility.Padding.LARGE,
            LumoUtility.Margin.Top.MEDIUM
        );

        return formContainer;
    }

    private void plantGrove() {
        if (repositoryUrl.isEmpty()) {
            Notification.show("Repository URL is required")
                .addThemeVariants(NotificationVariant.LUMO_ERROR);
            return;
        }

        try {
            // For now, use a random cultivator ID
            // TODO: Integrate proper authentication
            UUID cultivatorId = UUID.randomUUID();

            CreateGroveRequest request = new CreateGroveRequest(
                repositoryUrl.getValue(),
                branch.getValue(),
                name.getValue().isEmpty() ? null : name.getValue(),
                machineSize.getValue()
            );

            var grove = groveService.plantGrove(cultivatorId, request);

            Notification.show("Grove planted successfully! ID: " + grove.id())
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);

            getUI().ifPresent(ui -> ui.navigate(GrovesView.class));

        } catch (Exception e) {
            Notification.show("Failed to plant grove: " + e.getMessage())
                .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }
}
