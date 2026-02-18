package dev.orchard.canopy.view;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.theme.lumo.LumoUtility;
import dev.orchard.core.model.GroveState;
import dev.orchard.roots.entity.GroveEntity;
import dev.orchard.roots.repository.GroveRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;

@Route(value = "groves", layout = MainLayout.class)
@PageTitle("My Groves | Orchard Canopy")
public class GrovesView extends VerticalLayout {

    private final GroveRepository groveRepository;
    private Grid<GroveEntity> grid;

    @Autowired
    public GrovesView(GroveRepository groveRepository) {
        this.groveRepository = groveRepository;
        setSizeFull();
        setPadding(true);
    }

    @PostConstruct
    public void init() {
        add(createHeader());
        add(createGrid());
        refreshGrid();
    }

    private HorizontalLayout createHeader() {
        H2 title = new H2("My Groves");
        title.addClassNames(LumoUtility.Margin.NONE);

        RouterLink plantButton = new RouterLink("Plant Grove", PlantGroveView.class);
        Button btn = new Button("Plant Grove", new Icon(VaadinIcon.PLUS));
        btn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        btn.addClickListener(e -> getUI().ifPresent(ui -> ui.navigate(PlantGroveView.class)));

        HorizontalLayout header = new HorizontalLayout(title, btn);
        header.setWidthFull();
        header.setJustifyContentMode(JustifyContentMode.BETWEEN);
        header.setAlignItems(Alignment.CENTER);
        return header;
    }

    private Grid<GroveEntity> createGrid() {
        grid = new Grid<>(GroveEntity.class, false);
        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES);
        grid.setSizeFull();

        grid.addColumn(new ComponentRenderer<>(grove -> {
            Span name = new Span(grove.getName());
            name.getStyle().set("font-weight", "500");
            return name;
        })).setHeader("Name").setFlexGrow(2);

        grid.addColumn(new ComponentRenderer<>(grove -> {
            Span state = new Span(grove.getState().name());
            state.getElement().getThemeList().add(getStateTheme(grove.getState()));
            state.getStyle()
                .set("padding", "0.25rem 0.5rem")
                .set("border-radius", "var(--lumo-border-radius-s)")
                .set("font-size", "var(--lumo-font-size-s)");
            return state;
        })).setHeader("State").setWidth("120px");

        grid.addColumn(GroveEntity::getRepositoryUrl)
            .setHeader("Repository")
            .setFlexGrow(3);

        grid.addColumn(GroveEntity::getBranch)
            .setHeader("Branch")
            .setWidth("120px");

        grid.addColumn(new ComponentRenderer<>(grove -> {
            if (grove.getSeedlingIpAddress() != null && grove.getSeedlingSshPort() != null) {
                return new Span(grove.getSeedlingIpAddress() + ":" + grove.getSeedlingSshPort());
            }
            return new Span("-");
        })).setHeader("SSH").setWidth("150px");

        grid.addColumn(new ComponentRenderer<>(grove -> {
            HorizontalLayout actions = new HorizontalLayout();
            actions.setSpacing(true);

            if (grove.getState() == GroveState.FLOURISHING) {
                Button connect = new Button(new Icon(VaadinIcon.TERMINAL));
                connect.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
                connect.setTooltipText("Connect via SSH");
                actions.add(connect);
            }

            Button refresh = new Button(new Icon(VaadinIcon.REFRESH));
            refresh.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            refresh.setTooltipText("Refresh status");
            refresh.addClickListener(e -> refreshGrid());
            actions.add(refresh);

            return actions;
        })).setHeader("Actions").setWidth("100px");

        return grid;
    }

    private void refreshGrid() {
        grid.setItems(groveRepository.findAll());
    }

    private String getStateTheme(GroveState state) {
        return switch (state) {
            case FLOURISHING -> "badge success";
            case PREPARING, PLANTING, GROWING -> "badge";
            case DORMANT -> "badge contrast";
            case BLIGHTED -> "badge error";
            case CLEARING, CLEARED -> "badge contrast";
        };
    }
}
