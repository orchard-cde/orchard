package dev.orchard.canopy.view;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;
import com.vaadin.flow.theme.lumo.LumoUtility;
import dev.orchard.core.model.GroveState;
import dev.orchard.roots.repository.GroveRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;

@Route(value = "forest", layout = MainLayout.class)
@RouteAlias(value = "", layout = MainLayout.class)
@PageTitle("Forest Overview | Orchard Canopy")
public class ForestView extends VerticalLayout {

    private final GroveRepository groveRepository;

    @Autowired
    public ForestView(GroveRepository groveRepository) {
        this.groveRepository = groveRepository;
        setSizeFull();
        setPadding(true);
        setSpacing(true);
    }

    @PostConstruct
    public void init() {
        add(createHeader());
        add(createStatsCards());
        add(createGroveOverview());
    }

    private HorizontalLayout createHeader() {
        H2 title = new H2("Forest Overview");
        title.addClassNames(LumoUtility.Margin.NONE);

        HorizontalLayout header = new HorizontalLayout(title);
        header.setWidthFull();
        header.setJustifyContentMode(JustifyContentMode.BETWEEN);
        header.setAlignItems(Alignment.CENTER);
        return header;
    }

    private FlexLayout createStatsCards() {
        long totalGroves = groveRepository.count();
        long flourishingGroves = groveRepository.findByState(GroveState.FLOURISHING).size();
        long activeGroves = groveRepository.findActiveGroves().size();
        long dormantGroves = groveRepository.findByState(GroveState.DORMANT).size();

        FlexLayout cards = new FlexLayout();
        cards.setWidthFull();
        cards.setFlexWrap(FlexLayout.FlexWrap.WRAP);
        cards.getStyle().set("gap", "1rem");

        cards.add(createStatCard("Total Groves", String.valueOf(totalGroves), "var(--lumo-primary-color)"));
        cards.add(createStatCard("Flourishing", String.valueOf(flourishingGroves), "var(--lumo-success-color)"));
        cards.add(createStatCard("Active", String.valueOf(activeGroves), "var(--lumo-primary-color)"));
        cards.add(createStatCard("Dormant", String.valueOf(dormantGroves), "var(--lumo-contrast-50pct)"));

        return cards;
    }

    private Div createStatCard(String label, String value, String color) {
        Div card = new Div();
        card.addClassNames(
            LumoUtility.Background.CONTRAST_5,
            LumoUtility.BorderRadius.MEDIUM,
            LumoUtility.Padding.MEDIUM
        );
        card.getStyle()
            .set("min-width", "150px")
            .set("flex", "1");

        Span valueSpan = new Span(value);
        valueSpan.getStyle()
            .set("font-size", "2rem")
            .set("font-weight", "bold")
            .set("color", color);

        Span labelSpan = new Span(label);
        labelSpan.addClassNames(
            LumoUtility.FontSize.SMALL,
            LumoUtility.TextColor.SECONDARY
        );

        VerticalLayout content = new VerticalLayout(valueSpan, labelSpan);
        content.setPadding(false);
        content.setSpacing(false);

        card.add(content);
        return card;
    }

    private VerticalLayout createGroveOverview() {
        VerticalLayout overview = new VerticalLayout();
        overview.setSizeFull();
        overview.setPadding(false);

        H2 title = new H2("Recent Activity");
        title.addClassNames(LumoUtility.FontSize.LARGE);

        var recentGroves = groveRepository.findAll().stream()
            .sorted((a, b) -> b.getLastAccessedAt().compareTo(a.getLastAccessedAt()))
            .limit(5)
            .toList();

        if (recentGroves.isEmpty()) {
            Paragraph empty = new Paragraph("No groves planted yet. Plant your first grove to get started!");
            empty.addClassNames(LumoUtility.TextColor.SECONDARY);
            overview.add(title, empty);
        } else {
            overview.add(title);
            recentGroves.forEach(grove -> {
                Div groveItem = new Div();
                groveItem.addClassNames(
                    LumoUtility.Background.CONTRAST_5,
                    LumoUtility.BorderRadius.MEDIUM,
                    LumoUtility.Padding.MEDIUM,
                    LumoUtility.Margin.Bottom.SMALL
                );

                Span name = new Span(grove.getName());
                name.getStyle().set("font-weight", "bold");

                Span state = new Span(" - " + grove.getState().name());
                state.addClassNames(LumoUtility.TextColor.SECONDARY);

                Span repo = new Span(grove.getRepositoryUrl());
                repo.addClassNames(LumoUtility.FontSize.SMALL, LumoUtility.TextColor.SECONDARY);
                repo.getStyle().set("display", "block");

                groveItem.add(name, state, repo);
                overview.add(groveItem);
            });
        }

        return overview;
    }
}
